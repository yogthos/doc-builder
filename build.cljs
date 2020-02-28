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

(defn image? [node]
  (and (vector? node) (= :page/image (first node))))

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

(defn image->b64 [file-path {:keys [source]}]
  ;; todo handle keywords as document data
  (when file-path
    (let [format    (last (string/split file-path #"\."))]
      (str
       "data:image/" format ";base64, "
       (-> (path/resolve (str source path-sep file-path))
           (fs/readFileSync)
           (.toString "base64"))))))

(defn inject-image [node opts]
  (update-in node [1 :src] image->b64 opts))

(defn parse-path [path]
  (mapv keyword (string/split path #"\.")))

(defn parse-template [{:keys [template-name template data] :as opts}]
  (eval-functions
   (postwalk
    (fn [node]
      (cond
        (css? node)
        (map (partial inject-css template-name) (rest node))
        (image? node)
        (inject-image node opts)
        (data-node? node)
        (get-in data (parse-path (name node)))
        :else node))
    template)))

(defn gen-html [opts]
  (-> opts parse-template render-hiccup))

(defn parse-edn [file]
  (-> file slurp reader/read-string))

(defn template-file [template]
  (str "templates" path-sep (name template) path-sep "template.edn"))

(defn write-pdf [{:keys [browser pending target pdf-opts]} html]
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
                             (fn [_] (swap! pending dec)))
                          (.catch #(js/console.error (.-message %))))))
             (.catch #(js/console.error (.-message %))))))))

(defn write-html [{:keys [target]} html]
  (spit target html))

(defn ensure-build-folder-exists [target]
  (when-not (fs/existsSync target)
    (fs/mkdirSync target)))

(defn read-config []
  (reader/read-string (slurp "config.edn")))

(defn compile-document [{:keys [template formats source target] :as opts} document]
  (let [data (str source path-sep document)
        document (subs document 0 (.lastIndexOf document "."))
        opts     (merge opts
                        {:template-name (name template)
                         :template      (parse-edn (template-file template))
                         :data          (parse-edn data)})
        html     (gen-html opts)]
    (ensure-build-folder-exists target)
    (doseq [format formats]
      (println "generating" (string/upper-case (name format)) "document:" target)
      ((case format :pdf write-pdf :html write-html)
       (assoc opts :target (str target path-sep document "." (name format)))
       html))))

(defn parse-args []
  (->> (.-argv js/process)
       (drop-while #(not (string/starts-with? % "--")))
       (partition-by #(string/starts-with? % "--"))
       (partition-all 2)
       (reduce
        (fn [args [[k] v]]
          (assoc args
                 (keyword k)
                 (case k
                   "--docs" v
                   "--template" (-> (first v) (subs 1) keyword))))
        {})))

(let [args (parse-args)
      documents (:--docs args)
      template  (:--template args)
      config    (read-config)
      browser   (when (some #{:pdf} (:formats config))
                  (.launch puppeteer (clj->js {:args ["--no-sandbox" "--disable-setuid-sandbox"]})))
      pending   (when browser
                  (doto (atom (count documents))
                    (add-watch :watcher
                               (fn [_ _ _ pending]
                                 (when (zero? pending)
                                   (.then (js/Promise.resolve browser)
                                          #(.close %)))))))]
  (doseq [document documents]
    (compile-document
     (-> config
         (assoc :browser browser :pending pending)
         (update :template #(or template %)))
     document)))
