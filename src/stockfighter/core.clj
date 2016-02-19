(ns stockfighter.core
  (:gen-class)
  (:require [clojure.string :as string]
            [clojure.pprint :refer [pprint]]
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
  (string/join "/" parts))


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

(defn fprice
  [price]
  (if price
    (format "$%03d.%02d" (quot price 100) (rem price 100))
    "$---.--"))

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
  [client path]
    (let [ws-control (async/chan)
          ws-msg (async/chan)]
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


(defn debounce
  [chan delay-msec]
  (let [debounced (async/chan)]
    (async/go-loop
      [state :wait
       values []
       chan-with-timeout [chan]]
      (let [[chan timeout] chan-with-timeout
            [value chan-or-timeout] (async/alts! chan-with-timeout)]
        (condp = chan-or-timeout
          chan    (case state
                    :wait     (recur :debounce
                                     (conj values value)
                                     [chan (async/timeout delay-msec)])
                    :debounce (recur :debounce
                                     (conj values value)
                                     [chan (async/timeout delay-msec)]))
          timeout (do (async/>! debounced values)
                      (recur :wait [] [chan])))))
    debounced))

(defn tape-async
  [client account venue stock]
  (let [stock-path (path "wss://api.stockfighter.io/ob/api"
                         "ws" account "venues" venue "tickertape"
                         "stocks" stock)
        ws (ws-async client stock-path)]
  (debounce (async/map :quote [ws]) 50)))

(defn level-status-async
  [instanceId]
  (let [status (async/chan)]
    (async/go-loop
      []
      (async/>! status (level-status instanceId))
      (async/<! (async/timeout 1000))
      (recur))
    (async/unique status)))


(defn fps [price size] (str (fprice price) "×" (format "%05d" size)))
(defn fquote
  [{:keys [bid bidSize ask askSize last lastSize]}]
    (str
      "Bid: "  (fps bid bidSize) " "
      "Last: " (fps last lastSize) " "
      "Ask: "  (fps ask askSize)))

(defn fstatus
  [{:keys [state details flash]}]
  (str "Level " state
       ", day " (format "%d/%d"
                        (:tradingDay details) (:endOfTheWorldDay details))
       (string/join ""
                    (map (fn [[key msg]]
                           (str "\n" (string/capitalize (name key)) ": " msg))
                         flash))))


(defn print-quotes
  [quotes]
  (when-let [skipped (butlast quotes)]
    (println (format "Skipped %03d" (count skipped))))
  (println (fquote (last quotes))))

(defn printer-async
  [tape level-status]
  (async/go-loop
    []
    (async/alt!
      tape         ([quotes] (print-quotes quotes))
      level-status ([status] (println (fstatus status))))
    (recur)))


(defn extract-target-price
  [flash]
  (when-let [price-str
             (second (re-find #"target price is \$(\d+\.\d+)"
                              (get flash :info "")))]
    (int (* 100 (Float/parseFloat price-str)))))

(defn buy-stock-async
  [client [account venue stock] qty price]
  (let [stock-path (path "https://api.stockfighter.io/ob/api"
                         "venues" venue "stocks" stock "orders")
        headers {"X-Starfighter-Authorization" api-key}
        body (json/generate-string {:account account
                                    :venue venue
                                    :stock stock
                                    :price price
                                    :qty qty
                                    :direction :buy
                                    :orderType :immediate-or-cancel})]
    (async/thread (json/parse-string (http/string
                                       (http/POST client
                                                  stock-path
                                                  :body body
                                                  :headers headers))))))

(defn trader-async
  [client venue-stock tape level-status]
  (async/go-loop
    [state :wait-for-target-price
     target-price nil]
    (println state target-price)
    (case state
      :wait-for-target-price
      (if-let [target-price (-> (async/<! level-status)
                                (get :flash {})
                                extract-target-price)]
        (recur :buy target-price)
        (recur :get-target-price nil))

      :get-target-price
      (let [last-price (-> (async/<! tape) last :last)]
        (pprint (async/<! (buy-stock-async client venue-stock 100 last-price)))
        (recur :wait-for-target-price nil))

      :buy
      (let [{ask :ask ask-size :askSize} (last (async/<! tape))]
        (println "buy" ask ask-size)
        (when (and (> ask-size 0) (< ask target-price))
          (pprint (async/<! (buy-stock-async client venue-stock ask-size ask))))
        (recur :buy target-price)
      ))))


(defn level2-async
  "Level 2: 'Chock a block' using async"
  [& args]
  (with-open [client (http/create-client)]
    (ensure-api-up!)
    (let [{:keys [account instanceId], [venue] :venues, [stock] :tickers}
            (start-level "chock_a_block")
          target-qty 100000
          tape (async/mult (tape-async client account venue stock))
          level-status (async/mult (level-status-async instanceId))
          trader (trader-async client [account venue stock]
                              (async/tap tape (async/chan))
                              (async/tap level-status (async/chan)))]
      (async/<!! (printer-async (async/tap tape (async/chan))
                                (async/tap level-status (async/chan)))))))
