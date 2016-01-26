(ns stockfighter.core
  (:gen-class)
  (:require [clojure.pprint :refer [pprint]]
            [carica.core :refer [config configurer resources]]
            [clj-http.client :as client]
            [cheshire.core :as json]
            [http.async.client :as http]
            [clojure.core.async :as async])
  )

(def secrets-config (configurer (resources "secrets-config.edn")))

(def api-key (secrets-config :api-key))


(defn path
  "Helper for building paths, similar to str"
  [& parts]
  (clojure.string/join "/" parts))


(defn sf-request-url
  "Wrap clj-http requirest with stockfighter specific params and headers"
  [method url & [req-body req]]
  (:body (client/request
              (merge req {:method method
                          :url url
                          :as :json
                          :headers {"X-Starfighter-Authorization" api-key}
                          :form-params req-body
                          :content-type :json
                          }))))

(defn sf-request
  "Wrap sf-request-url with stockfighter game api url"
  [method path & [req-body]]
  (sf-request-url method (str "https://api.stockfighter.io/ob/api/" path) req-body))

(defn sf-gm-request
  "Wrap sf-request-url with stockfighter gm api url"
  [method path & [req-body]]
  (sf-request-url method (str "https://api.stockfighter.io/gm/" path) req-body))


(defn ensure-api-up!
  "Check heartbeat api endpoint and throw exception if it's down"
  []
  (let [{:keys [ok error]} (sf-request :get "heartbeat")]
    (when-not ok (throw (Exception. (str "Api down: " error))))))

(defn get-levels
  []
  (sf-gm-request :get "levels"))


(defn start-level
  [name]
  "Start level with a given name unless it was already started"
  (sf-gm-request :post (path "levels" name)))

(defn level-status
  [instanceId]
  (sf-gm-request :get (path "instances" instanceId)))



(defn level1
  "Level 1: 'First steps'"
  [& args]
  (ensure-api-up!)
  (let [{:keys [account instanceId], [venue] :venues, [stock] :tickers}
          (start-level "first_steps")
        stock-path (path "venues" venue "stocks" stock)
        get-ask-price #(:ask (sf-request :get (path stock-path "quote")))
        buy (fn [qty price] (sf-request :post (path stock-path "orders")
                                        {:account account
                                         :orderType :limit
                                         :direction :buy
                                         :qty qty
                                         :price price
                                         }))]
    (println account venue stock)
    (pprint (level-status instanceId))
    (println "Ask price" (get-ask-price))
    (pprint (buy 100 10000))
    (pprint (level-status instanceId))))



(defn level2
  "Level 2: 'Chock a block'"
  [& args]
  (ensure-api-up!)
  (let [{:keys [account instanceId], [venue] :venues, [stock] :tickers}
          (start-level "chock_a_block")
        target-qty 100000
        stock-path (path "venues" venue "stocks" stock)
        get-quote #(sf-request :get (path stock-path "quote"))
        buy (fn [qty price] (sf-request :post (path stock-path "orders")
                                        {:account account
                                         :orderType :immediate-or-cancel
                                         :direction :buy
                                         :qty qty
                                         :price price
                                         }))
        extract-target-price
        (fn [flash]
          (when-let [price-str
                     (second (re-find #"target price is \$(\d+\.\d+)"
                                      (get flash :info "")))]
            (int (* 100 (Float/parseFloat price-str)))))]

    (loop [target-price 100000]
      (let [{:keys [flash state] :as status} (level-status instanceId)
            target-price (or (extract-target-price flash) target-price)]
        (when flash (println target-price flash))
        (if (= state "open")
          (do
            (let [{:keys [ask askSize]} (get-quote)]
              (println ask askSize)
              (if (and (> askSize 0) (<= ask target-price))
                (pprint (buy askSize ask))))
            (Thread/sleep 1000)
            (recur target-price))
          (pprint status))))))



(defn println-t
  [& args]
  (apply println (str "t" (.getId (Thread/currentThread)) ":") args))


(defn ws-connect
  [client path ws-control ws-msg]
  (http/websocket
    client
    path
    :open  (fn [ws]
             (async/put! ws-control [:connected]))
    :close (fn [ws code reason]
             (async/put! ws-control [:disconnected code reason]))
    :error (fn [ws e] (println "ERROR:" e))
    :text  (fn [ws msg]
             (async/put! ws-msg (json/parse-string msg true))
             )))

(defn ws-async
  [path]
    (let [ws-control (async/chan)
          ws-msg (async/chan)
          client (http/create-client)]
      (async/go-loop
        [state [:started]]
        (let [[state & details] (or state (async/<! ws-control))]
          (apply println-t "Socket state:" (name state) details)
          (case state
            (:started :disconnected)
            (do
              (ws-connect client path ws-control ws-msg)
              (recur [:connecting path]))

            (recur nil))))
      ws-msg))

(defn level2-async
  "Level 2: 'Chock a block' using async"
  [& args]
  (ensure-api-up!)
  (let [{:keys [account instanceId], [venue] :venues, [stock] :tickers}
          (start-level "chock_a_block")
        target-qty 100000
        stock-path (path "wss://api.stockfighter.io/ob/api"
                         "ws" account "venues" venue "tickertape"
                         "stocks" stock)
        tape (ws-async stock-path)]
    (async/<!! (async/go-loop [] (pprint (async/<! tape)) (recur)))))
