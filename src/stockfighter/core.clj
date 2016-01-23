(ns stockfighter.core
  (:gen-class)
  (:require [clojure.pprint :refer [pprint]]
            [carica.core :refer [config configurer resources]]
            [clj-http.client :as client])
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


(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (ensure-api-up!)
  (let [{:keys [account instanceId], [venue] :venues, [stock] :tickers}
          (start-level "first_steps")
        stock-path (path "venues" venue "stocks" stock)
        get-ask-price #(:ask (sf-request :get (path stock-path "quote")))
        buy (fn [qty price] (sf-request :post (path stock-path "orders")
                                        {:account account
                                         :orderType :immediate-or-cancel
                                         :direction :buy
                                         :qty qty
                                         :price price
                                         }))]
    (println account venue stock)
    (pprint (level-status instanceId))
    (pprint (get-ask-price))
    (pprint (buy 1 10000))))
