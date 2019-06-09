(defproject stockfighter "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [clj-http "2.0.1"]
                 [cheshire "5.5.0"]
                 [http.async.client "1.0.1"]
                 [org.slf4j/slf4j-nop "1.7.18"]
                 [org.clojure/core.async "0.2.374"]
                 [sonian/carica "1.2.1"]]
  :main ^:skip-aot stockfighter.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
