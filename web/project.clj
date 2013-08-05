(defproject cryoweb "0.0.1"
  :description "A simple example of how to use lein-cljsbuild"
  :source-paths ["src/main/clj"]
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [prismatic/dommy "0.1.1"]
                 ]
  :plugins [[lein-cljsbuild "0.3.2"]]
  :cljsbuild {
    :builds [{:source-paths ["src/main/cljs"]
              :compiler {:output-to "target/main.js"
                         :optimizations :whitespace
                         :pretty-print true}}]})