#!/usr/bin/env lumo
(ns build.core
  (:require
   [cljs.reader :as reader]
   [clojure.string :as string]
   [clojure.walk :refer [prewalk postwalk]]
   [lumo.core :as lumo]
   ["fs" :as fs]
   ["path" :as path]
   ["puppeteer" :as puppeteer]))

;;; Hiccup parser ;;;
(defn normalize-body [body]
  (if (coll? body) (apply str (doall body)) (str body)))

(defn as-str
  "Converts its arguments into a string using to-str."
  [& xs]
  (apply str (map normalize-body xs)))

(defn escape-html
  "Change special characters into HTML character entities."
  [text]
  (-> (as-str text)
      (string/replace #"&" "&amp;")
      (string/replace #"<" "&lt;")
      (string/replace #">" "&gt;")
      (string/replace #"'" "&apos;")))

(defn xml-attribute [id value]
  (str " " (as-str (name id)) "=\"" (escape-html value) "\""))

(defn render-attribute [[name value]]
  (cond
    (true? value) (xml-attribute name name)
    (not value) ""
    :else (xml-attribute name value)))

(defn render-attr-map [attrs]
  (apply str (sort (map render-attribute attrs))))

(defn- merge-attributes [{:keys [id class]} map-attrs]
  (->> map-attrs
       (merge (if id {:id id}))
       (merge-with #(if %1 (str %1 " " %2) %2) (if class {:class class}))))

(defn normalize-element [[tag & content]]
  (let [re-tag    #"([^\s\.#]+)(?:#([^\s\.#]+))?(?:\.([^\s#]+))?"
        [_ tag id class] (re-matches re-tag (as-str (name tag)))
        tag-attrs {:id    id
                   :class (when class (string/replace class #"\." " "))}
        map-attrs (first content)]
    (if (map? map-attrs)
      [tag (merge-attributes tag-attrs map-attrs) (next content)]
      [tag tag-attrs content])))

(defn render-element [[tag attrs & content]]
  (str "<" (name tag) (render-attr-map attrs) ">" (as-str (flatten content)) "</" (name tag) ">"))

(defn render-hiccup [hiccup]
  (postwalk
   (fn [node]
     (if (and (not (map-entry? node))(vector? node))
       (-> node normalize-element render-element)
       node))
   hiccup))

;;; template parser ;;;
(def path-sep (.-sep path))

(defn css? [node]
  (and (vector? node) (= :page/css (first node))))

(defn data-node? [node]
  (and (keyword? node) (= "data" (namespace node))))

(defn eval-functions [template]
  (prewalk
   (fn [node]
     (if (list? node)
       (lumo/eval node)
       node))
   template))

(defn slurp [filename & {:keys [encoding]}]
  (.toString
   (if encoding
     (fs/readFileSync filename encoding)
     (fs/readFileSync filename))))

(defn spit [filename data & {:keys [encoding mode flag]
                             :or   {encoding "utf8"
                                    mode     "0o666"
                                    flag     "w"}}]
  (let [data (if (string? data) data (str data))]
    (fs/writeFileSync filename data encoding mode flag)))

(defn template-file-path [template-name file]
  (str "templates" path-sep template-name path-sep file))

(defn inject-css [template-name ref]
  [:style
   {:type "text/css"}
   (slurp (template-file-path template-name ref))])

(defn parse-path [path]
  (mapv keyword (string/split path #"\.")))

(defn parse-template [{:keys [template-name template format data]}]
  (eval-functions
   (postwalk
    (fn [node]
      (cond
        (css? node)
        (map (partial inject-css template-name) (rest node))
        (data-node? node)
        (get-in data (parse-path (name node)))
        :else node))
    template)))

(defn gen-html [data]
  (-> data parse-template render-hiccup))

(defn parse-edn [file]
  (-> file slurp reader/read-string))

(defn template-file [template]
  (str "templates" path-sep (name template) path-sep "template.edn"))

(defn write-pdf [{:keys [target pdf-opts]} html]
  (let [browser (.launch puppeteer (clj->js {:args ["--no-sandbox" "--disable-setuid-sandbox"]}))]   
    (-> browser
     (.then #(.newPage %))   
     (.then
        (fn [page _]
          (-> (.setContent page html)
              (.then #(.emulateMedia page "screen"))
              (.then (fn [_ _]
                         (-> (.pdf page (clj->js (merge {:path target 
                                                         :format "Letter"
                                                         :printBackground true}
                                                        pdf-opts)))
                             (.then
                              (fn [pdf]
                                (.then (js/Promise.resolve browser) #(.close %))))
                             (.catch #(js/console.error (.-message %))))))              
              (.catch #(js/console.error (.-message %)))))))))

(defn write-html [{:keys [target]} html]
  (spit target html))

(defn ensure-build-folder-exists [target]
  (when-not (fs/existsSync target)
    (fs/mkdirSync target)))

(defn read-config []
  (reader/read-string (slurp "config.edn")))

(defn main []
  (let [{:keys [template document formats target]} (read-config)
        data (str "documents" path-sep document)
        document (subs document 0 (.lastIndexOf document "."))]
    (ensure-build-folder-exists target)
    (doseq [format formats]
      (let [target (str target path-sep document "." (name format))
            opts {:format format
                  :template-name (name template)
                  :template (parse-edn (template-file template))
                  :data     (parse-edn data)
                  :target target}]
        (println "generating" (string/upper-case (name format)) "document:" target)
        (->> (gen-html opts)
             ((case format :pdf write-pdf :html write-html) opts))))))

(main)
