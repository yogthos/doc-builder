(defproject doc-builder "0.1.0"
  :description "data driven HTML/PDF document builder"
  :url "https://github.com/yogthos/doc-builder"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/clojurescript "1.10.773"]]
  :plugins [[lein-cljsbuild "1.1.7"]]
  :cljsbuild {:builds [{:id "release"
                        :source-paths ["src"]
                        :compiler {:main doc-builder.core
                                   :output-to "target/doc-builder.js"
                                   :target :nodejs
                                   :optimizations :simple
                                   :npm-deps {"puppeteer" "3.3.0"}
                                   :install-deps true}}]})
