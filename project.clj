<<<<<<< HEAD
(defproject cryoweb "0.0.1"
  :description "A simple example of how to use lein-cljsbuild"
  :source-paths ["src/main/clj"]
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [prismatic/dommy "0.1.1"]]
  :plugins [[lein-cljsbuild "0.3.2"]]
  :cljsbuild {
    :builds [{:source-paths ["src/main/cljs"]
              :compiler {:output-to "build/generated-resources/webapp/javascript/main.js"
                         :output-dir "build/cljs-temp"
                         :optimizations :whitespace
=======
(defproject cryoweb "0.0.1"
  :description "A simple example of how to use lein-cljsbuild"
  :source-paths ["src/main/clj"]
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [prismatic/dommy "0.1.1"]]
  :plugins [[lein-cljsbuild "0.3.2"]]
  :cljsbuild {
    :builds [{:source-paths ["src/main/cljs"]
              :compiler {:output-to "build/generated-resources/webapp/javascript/main.js"
                         :output-dir "build/cljs-temp"
                         :optimizations :whitespace
>>>>>>> 3853d1f2bc9b31a315b369c9cbbb717625d37f70
                         :pretty-print true}}]})