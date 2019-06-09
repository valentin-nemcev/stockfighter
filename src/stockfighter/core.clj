(ns stockfighter.core
  (:gen-class)
  (:require [clojure.string :as string]
            [clojure.pprint :refer [pprint]]
            [carica.core :refer [config configurer resources]]
            [cheshire.core :as json]
            [http.async.client :as http]
            [http.async.client.request :as http.request]
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
  [client method url & [req-body req]]

  (let [headers {"X-Starfighter-Authorization" api-key}
        body (json/generate-string req-body)
        final-req (merge req {:body body :headers headers})
        resp (http.request/execute-request client (apply http.request/prepare-request
                                                 method
                                                 url
                                                 (apply concat final-req)))
        resp-str (http/string (http/await resp))]
    (json/parse-string resp-str true)))


(defn sf-request
  "Wrap sf-request-url with stockfighter game api url"
  [client method path & [req-body]]
  (sf-request-url client method (str "https://api.stockfighter.io/ob/api/" path) req-body))

(defn sf-gm-request
  "Wrap sf-request-url with stockfighter gm api url"
  [client method path & [req-body]]
  (sf-request-url client method (str "https://api.stockfighter.io/gm/" path) req-body))


(defn ensure-api-up!
  "Check heartbeat api endpoint and throw exception if it's down"
  [client]
  (let [{:keys [ok error]} (sf-request client :get "heartbeat")]
    (when-not ok (throw (Exception. (str "Api down: " error))))))

(defn get-levels
  [client]
  (sf-gm-request client :get "levels"))


(defn level-status
  [client instanceId]
  (sf-gm-request client :get (path "instances" instanceId)))

(defn start-level
  [client name]
  "Start level with a given name, restarts it if it was already running"
  (println (str "Starting level " name "..."))
  (let [{:keys [account instanceId], [venue] :venues, [stock] :tickers :as resp}
        (sf-gm-request client :post (path "levels" name))
        status (level-status client instanceId)
        day (get-in status [:details :tradingDay] 0)]
    (if (> day 0)
      (do (println (str "Level already running at day " day ", stopping..."))
          (sf-gm-request client :post (path "instances" instanceId "stop"))
          (start-level client name))
      {:account account :instanceId instanceId :venue venue :stock stock})))


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
  [socket-name client path]
    (let [ws-control (async/chan)
          ws-msg (async/chan)]
      (async/go-loop
        [state [:started]]
        (let [[state & details] (or state (async/<! ws-control))]
          (println socket-name "socket" (name state)
                          (string/join " " details))
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
        ws (ws-async "Tape" client stock-path)]
  (debounce (async/map :quote [ws]) 50)))

(defn level-status-async
  [client instanceId]
  (let [status (async/chan)]
    (async/go-loop
      []
      (async/>! status (level-status client instanceId))
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
  [tape level-status trader-status]
  (async/go-loop
    []
    (async/alt!
      tape         ([quotes] (print-quotes quotes))
      level-status ([status] (println (fstatus status)))
      trader-status (
                     [[state & details]]
                     (println (name state) (string/join " " details))))
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
  [client venue-stock tape order-updates level-status]
  (let [status (async/chan)]
    (async/go-loop
      [state :wait-for-target-price
      target-price nil]
      (async/>! status [state target-price])
      (case state
        :wait-for-target-price
        (if-let [target-price (-> (async/<! level-status)
                                  (get :flash {})
                                  extract-target-price)]
          (recur :buy target-price)
          (recur :get-target-price nil))

        :get-target-price
        (let [last-price (-> (async/<! tape) last :last)]
          (order-updates (async/<! (buy-stock-async
                              client
                              venue-stock
                              100
                              last-price)))
          (recur :wait-for-target-price nil))

        :buy
        (let [{ask :ask ask-size :askSize} (last (async/<! tape))]
          (when (and (> ask-size 0) (< ask target-price))
            (pprint (async/<! (buy-stock-async client
                                               venue-stock
                                               ask-size
                                               ask))))
          (recur :buy target-price)
        )))
    (async/unique status)))


(defn level2-async
  "Level 2: 'Chock a block' using async"
  [& args]
  (with-open [client (http/create-client)]
    (ensure-api-up! client)
    (let [{:keys [account instanceId venue stock]}
            (start-level client "chock_a_block")
          target-qty 100000
          tape (async/mult (tape-async client account venue stock))
          level-status (async/mult (level-status-async client instanceId))
          trader-status (async/mult (trader-async client [account venue stock]
                              (async/tap tape (async/chan))
                              (async/tap level-status (async/chan (async/sliding-buffer 1)))))]
      (async/<!! (printer-async (async/tap tape (async/chan))
                                (async/tap level-status (async/chan))
                                (async/tap trader-status (async/chan)))))))
