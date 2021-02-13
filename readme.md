# Clojure Coinbase FIX example

A very basic/minimal example of communication with Coinbase using [their FIX api](https://docs.pro.coinbase.com/#fix-api) with clojure. Not meant to be complete or robust but might be useful as an example or starting point. This doesn't use external libraries for FIX encoding/decoding, just does it manually. A good overview of the FIX message structure is [here](https://library.tradingtechnologies.com/tt-fix/gateway/Structure_Overview.html)

[This](https://github.com/jvirtanen/coinbase-fix-example#usage) provides some good instructions for setting up the required SSL Tunnel.

The stunnel config should look something like this. You will need to and generate a CAfile.
```

[CoinbaseSandbox]
client = yes
accept = 4198
connect = fix-public.sandbox.pro.coinbase.com:4198
verify = 4
CAfile = /path/to/fix-public.sandbox.pro.coinbase.com.pem
```

Also assumes you have an environment variable or variables with your coinbase credentials. You will need to get your credentials in the following format:
```
{:secret "my secret",
 :key "my key",
 :passphrase "my passphrase"}
 ```
 
I just use the printed string of this directly in the environmental variable `"CB-SANDBOX-CREDS"`.

## usage

```
(require [fix :refer :all])

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
            :Symbol (:Symbol o)})}])


```


[Full "Library" source](src/fix.clj).
