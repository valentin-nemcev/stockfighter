(ns stockfighter.core
  (:gen-class)
  (:require [carica.core :refer [config configurer resources]])
  )

(def secrets-config (configurer (resources "secrets-config.edn")))

(def api-key (secrets-config :api-key))


(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (println "Api key is" api-key))
