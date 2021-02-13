(ns fix
  (:require [clojure.core.async :as a
             :refer [<! >! alt! alts! alt!! chan go go-loop poll! <!! >!!]]
            [clojure.data.codec.base64 :as b64]
            [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import
   [java.io DataInputStream DataOutputStream]
   [java.net InetSocketAddress Socket]
   [java.time Instant ZoneId]
   java.nio.charset.StandardCharsets
   java.time.format.DateTimeFormatter
   javax.crypto.Mac
   javax.crypto.spec.SecretKeySpec))

(defn random-uuid [] (java.util.UUID/randomUUID))

;;; custom tags used by coinbase (incomplete), or not in xml spec
(def cb-tag->field
  {8013 :CancelOrdersOnDisconnect
   9406 :DropCopyFlag
   7928	:SelfTradePrevention
   8014 :BatchID})

(def cb-msg-types
  {:NewOrderBatch "U6"
   :OrderCancelBatch "U4"})

;;from quickfixj dependency. Though coinbase uses fix 4.2 as a baseline, they
;;use tags from later versions and using 4.3 seems more complete for the tags we
;;use in this demo. Such as: [554 Password]
(def fixxml (-> (io/resource "FIX43.xml") ;;42 is missing password
                io/reader
                xml/parse))

;;; get a tree-seq that will make queries easier
(def fixxml-seq (xml-seq fixxml))

(def tag->field
  (->> fixxml-seq
       (keep
        (fn [{:keys [attrs]}]
          (let [{:keys [name number type]} attrs]
            (when (and number name)
              [(Long/parseLong number) (keyword name)]))))
       (into cb-tag->field)))

(def field->tag
  (set/map-invert tag->field))

(def ->msg-type
  (->> fixxml-seq
       (keep
        (fn [{:keys [attrs]}]
          (let [{:keys [name msgtype msgcat]} attrs]
            (when (and msgtype name)
              [(keyword name) msgtype]))))
       (into cb-msg-types)))

(defn component->field-names [component-key]
  (->> fixxml-seq
       (filter (fn [{:keys [tag]}] (= tag component-key)))
       first
       :content
       (keep (fn [{:keys [attrs]}] (when-let [n (:name attrs)] (keyword n))))))

(def header-fields (component->field-names :header))

(def trailer-fields (component->field-names :trailer))

;;; signing

(defn hmac-sha-256 [secret-key s]
  {:pre [(bytes? secret-key) (string? s)]}
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. secret-key "HmacSHA256"))
    (.doFinal mac (.getBytes s "utf-8"))))

(defn ->sig [creds {Body                   :Body
                    {:keys [MsgType
                            MsgSeqNum
                            SendingTime
                            TargetCompID]} :Header}]
  {:pre [SendingTime MsgType MsgSeqNum (:key creds) TargetCompID (:passphrase creds)]}
  (let [prehash        (str/join (char 1)
                                 [SendingTime
                                  MsgType
                                  MsgSeqNum
                                  (:key creds)
                                  TargetCompID
                                  (:passphrase creds)])
        secret-decoded (b64/decode (.getBytes (:secret creds)))
        hmac           (hmac-sha-256 secret-decoded prehash)]
    (-> hmac b64/encode String.)))

(defn add-field [acc [t f]]
  (if (sequential? f)
    (reduce conj
            (conj acc [t (count f)])
            (mapcat #(reduce add-field [] %) f))
    (conj acc [t f])))

(defn fixstr [pairs]
  (str
   (->> pairs
        (map (fn [[t v]] (str t "=" v)))
        (str/join (char 1)))
   (char 1)))

;;; copied from defunct clj-fix project
(defn checksum
  "Returns a 3-character string (left-padded with zeroes) representing the
   checksum of msg calculated according to the FIX protocol."
  [msg]
  (format "%03d" (mod (reduce + (.getBytes msg)) 256)))


(defn encode-msg [creds {:keys [Header Body]
                         :as   msg}]
  (let [{:keys [BeginString
                MsgType
                MsgSeqNum]}    Header
        {:keys [SendingTime
                TargetCompID]} Body

        sig          (->sig creds msg)
        after-bod-len
        (->> (assoc Body :RawData sig)
             (into (dissoc Header :BeginString :BodyLength))
             ;; msg type must be 3rd
             ((fn [m] (into [(find m :MsgType)] (dissoc m :MsgType))))
             (reduce add-field [])
             (map (fn [[f v]]
                    (if-let [t (field->tag f)]
                      [t v]
                      (throw (ex-info "no tag found fo field" {:field f :msg msg})))))
             fixstr)
        bod-ct       (count after-bod-len)
        top          (str (fixstr [[8 BeginString] [9 bod-ct]]))
        pre-trailer      (str top after-bod-len)
        trailer      (fixstr [[10 (checksum pre-trailer)]])]
    (str pre-trailer trailer)))

(defn decode-msg [s]
  (let [m (->> (str/split s #"\x01")
               (map (fn [pair-str]
                      (str/split pair-str #"=")))
               (map (fn [[t v]]
                      [(try (tag->field (Long/parseLong t) t)
                            (catch Throwable e t)) v]))
               (into {}))
        header         (select-keys m header-fields)
        trailer        (select-keys m trailer-fields)
        bod            (apply dissoc m (concat header-fields trailer-fields))]
    {:Header  header
     :Trailer trailer
     :Body    bod}))


(defn msg->bytes [s] (.getBytes s StandardCharsets/US_ASCII))


(defn read-in [^DataInputStream in]
  (let [i (.available in)]
    (when (pos? i)
      (let [ba (byte-array i)]
        (.read in ba)
        ba))))

(defn socket-open? [socket]
  (not (or (.isClosed socket) (.isInputShutdown socket) (.isOutputShutdown socket))))

(def fix-utc-fmt (DateTimeFormatter/ofPattern "yyyyMMdd-HH:mm:ss.SSS"))

(defn new-initiator [creds]
  {:seqnum (atom 0)
   :recv-ch (chan 100)
   :send-ch (chan 10)
   :creds creds})

(defn send! [{:keys [send-ch]} msgv]
  (a/put! send-ch msgv))

;;; todo: properly handle other session messages test request, resend, etc.
(defn start-initiator [{:keys [creds seqnum recv-ch send-ch
                               login] :as this}]
  (let [sock (doto (Socket.) (.connect (InetSocketAddress. "localhost" 4198)))
        in-stream (DataInputStream.  (.getInputStream sock))
        out-stream (DataOutputStream. (.getOutputStream sock))
        login (merge {:EncryptMethod            0
                      :HeartBtInt               30
                      :Password                 (:passphrase creds)
                      :CancelOrdersOnDisconnect "Y"}
                     login)
        {:keys [HeartBtInt]} login]
    (future
      (try
        (while (socket-open? sock)
          (when-let [i (read-in in-stream)]
            (a/put! recv-ch (decode-msg (String. i)))))
        (catch Throwable t (println "Error:" t)))
      (println "socket loop stopped"))
    (a/thread
      (loop []
        (alt!!
          send-ch ([[msg-type body]]
                   (.write out-stream
                           (msg->bytes
                            (encode-msg creds
                                        {:Header
                                         {:BeginString  "FIX.4.2"
                                          :MsgType      (->msg-type msg-type)
                                          :SenderCompID (:key creds)
                                          :TargetCompID "Coinbase"
                                          :SendingTime  (.format fix-utc-fmt (.atZone (Instant/now) (ZoneId/of "UTC")))
                                          :MsgSeqNum    (swap! seqnum inc)}
                                         :Body body}))))

          (a/timeout (* HeartBtInt 1000)) (send! this [:Heartbeat]))
        (when (socket-open? sock)
          (recur))))
    (send! this [:Logon login])
    (fn stop! []
      (do
        (.shutdownInput sock)
        (.shutdownOutput sock)
        (.close sock)))))


(comment
  (def cb-creds (read-string (System/getenv "CB-SANDBOX-CREDS")))

  (def i1 (new-initiator cb-creds))

  (go-loop []
    (when-some [x (<! (:recv-ch i1))]
      (println "new msg:" x)
      (recur)))

  (def stop! (start-initiator i1))

  (stop!)

  (def o1 (random-uuid))

;;; send takes an initiator and a vector of [msg-type {order body}]
  (send! i1 [:NewOrderSingle
             {:ClOrdID     o1
              :Symbol      "BTC-USD"
              :Side        1
              :Price       40000
              :OrderQty    0.001
              :OrdType     2
              :TimeInForce 1}])

  (send! i1 [:OrderCancelRequest
             {:ClOrdID     (random-uuid)
              :OrigClOrdID o1
              :Symbol      "BTC-USD"
             ;;:Text "not sure what this is for"
              }])

 ;;repeating groups managed with their "count" field, as per
 ;;https://github.com/FIXTradingCommunity/fix-json-encoding-spec/blob/master/Encoding_FIX_using_JSON-User_Guide.md#repeating-groups


  (def batch-orders
    [{:ClOrdID     (random-uuid)
      :Symbol      "BTC-USD"
      :Side        1
      :Price       5
      :OrderQty    0.001
      :OrdType     2
      :TimeInForce 1}
     {:ClOrdID     (random-uuid)
      :Symbol      "BTC-USD"
      :Side        2
      :Price       500000
      :OrderQty    0.001
      :OrdType     2
      :TimeInForce 1}])

  (send! i1
         [:NewOrderBatch
          {:BatchID (random-uuid)
           :NoOrders batch-orders}])

  (send! i1
         [:OrderCancelBatch
          {:BatchID (random-uuid)
           :NoOrders
           (for [o batch-orders]
             {:OrigClOrdID (:ClOrdID o)
              :Symbol (:Symbol o)})}]))
