(ns build
  "bb uberjar (from the workspace root) → target/{{main}}.jar.

   Only the main namespace is AOT-compiled; the components stay as source in
   the jar, because taia.ds discovers them at runtime (`requiring-resolve` on
   `<search-path>.<group>.<name>`)."
  (:require [clojure.tools.build.api :as b]))

(def main '{{top/ns}}.{{main/ns}}.web.core)
(def class-dir "target/classes")
(def uber-file "target/{{main}}.jar")

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (let [basis (b/create-basis {:project "deps.edn"})]
    (b/compile-clj {:basis basis
                    :ns-compile [main]
                    :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis basis
             :main main})
    (println "Built" uber-file)))
