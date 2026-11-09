;; Generates docs/api.md from every brick interface namespace. Run with: bb api-index
(ns api-index
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [edamame.core :as e]))

(def out-file "docs/api.md")

(defn interface-files []
  (->> (fs/glob "components" "*/src/**/interface.{clj,cljc}")
       (map str)
       sort))

(defn brick-of [path] (second (str/split path #"/")))

(defn parse-forms [path]
  (try
    (e/parse-string-all (slurp path)
                        {:all true
                         :auto-resolve (fn [alias] (if (= :current alias) 'current.ns (symbol (str alias))))
                         :readers (fn [_tag] identity)
                         :features #{:clj}
                         :read-cond :allow})
    (catch Exception ex
      (binding [*out* *err*]
        (println "WARN: failed to parse" path "-" (ex-message ex)))
      [])))

(defn ns-name-of [forms]
  (some #(when (and (seq? %) (= 'ns (first %))) (second %)) forms))

(defn first-doc-line [s]
  (some-> s str/split-lines first str/trim))

(defn defn-info [form]
  (let [[_ fname & body] form
        private? (:private (meta fname))
        ret (when (= :- (first body)) (second body))
        body (if (= :- (first body)) (drop 2 body) body)
        [doc body] (if (string? (first body)) [(first body) (rest body)] [nil body])
        body (if (map? (first body)) (rest body) body)
        arglists (cond
                   (vector? (first body)) [(first body)]
                   :else (->> body (filter seq?) (map first) (filter vector?)))]
    (when-not private?
      {:name fname :doc doc :arglists arglists :ret ret})))

(defn def-info [form]
  (let [[_ dname & body] form
        private? (:private (meta dname))
        doc (when (and (= 2 (count body)) (string? (first body))) (first body))]
    (when-not private?
      {:name dname :doc doc :def? true})))

(defn form-info [form]
  (when (and (seq? form) (symbol? (first form)))
    (case (name (first form))
      ("defn" "defmacro") (defn-info form)
      ("def" "defonce") (def-info form)
      nil)))

(defn render-entry [{:keys [name doc arglists ret def?]}]
  (str "- `" name
       (when (seq arglists) (str " " (str/join " " (map pr-str arglists))))
       (when ret (str " :- " (pr-str ret)))
       (when def? " (def)")
       "`"
       (when doc (str " — " (first-doc-line doc)))))

(defn render-file [path]
  (let [forms (parse-forms path)
        entries (keep form-info forms)]
    (str "## `" (brick-of path) "` — `" (or (ns-name-of forms) "?") "`\n\n"
         "[" path "](../" path ")\n\n"
         (if (seq entries) (str/join "\n" (map render-entry entries)) "_(no public defs)_")
         "\n")))

(let [files (interface-files)
      md (str "# API index\n\n"
              "> GENERATED — do not edit. Regenerate with `bb api-index`.\n"
              "> One section per brick: its `interface` namespace and public fns/defs. "
              "Everything outside these namespaces is internal and may change without notice.\n\n"
              (str/join "\n" (map render-file files)))]
  (spit out-file md)
  (println "Wrote" out-file "(" (count files) "interface files )"))
