(ns taia.ds.datastar.adapters.handlers.discovery-test
  "Discovery resolves components by naming convention, so these tests need a
   namespace to find. The brick ships no example components; `install-fixture!`
   defines one in memory under the default search path instead."
  (:require
   [hyperfiddle.rcf :refer [tests]]
   [spy.core :as spy]
   [taia.ds.datastar.adapters.handlers.discovery :as discovery
    :refer [discover-component set-component-search-paths! warm-cache!]]
   [taia.ds.datastar.adapters.handlers.discovery-cache :as cache]))

(def prefix "taia.ds.datastar.test-fixture")
(def component-ns (symbol (str prefix ".fixture.widget")))
(def controller-ns (symbol (str prefix ".fixture.widget-controller")))

(defn install-fixture!
  "Defines `fixture.widget` (render fn `widget`) and `fixture.widget-controller`
   (a `machine` var) in memory and marks both libs loaded, so the `require`
   that discovery does finds them without a file on the classpath, and points
   the search paths at `prefix`. Idempotent; every block calls it first because
   the search paths are one global the other test namespaces set too."
  []
  (binding [*ns* *ns*]
    (eval `(do (ns ~component-ns)
               (defn ~'widget [ctx#] [:div (get-in ctx# [:memory :n])])
               (ns ~controller-ns)
               (def ~'machine {:id      :widget
                               :initial :displayed
                               :states  {:displayed {:on {:INIT {}}}}}))))
  (dosync (commute @#'clojure.core/*loaded-libs* conj component-ns controller-ns))
  (set-component-search-paths! [prefix]))

(def !search-paths @#'discovery/!component-search-paths)

(tests
 (install-fixture!)

 "discovery via the first search path"
 (let [result (discover-component "fixture.widget")]
   (var? (:machine result)) := true
   (fn? (deref (:component-fn result))) := true)

 "discovery falls through to a later search path"
 (let [original @!search-paths]
   (set-component-search-paths! ["taia.ds.nonexistent.components" prefix])
   (let [result (discover-component "fixture.widget")]
     (var? (:machine result)) := true
     (fn? (deref (:component-fn result))) := true)
   (set-component-search-paths! original))

 "the error names the searched paths when nothing matches"
 (try
   (discover-component "nonexistent.component")
   :no-throw
   (catch clojure.lang.ExceptionInfo e
     (let [msg (.getMessage e)
           data (ex-data e)]
       (clojure.string/includes? msg "Searched paths:") := true
       (contains? data :searched-paths) := true)))

 (discover-component "nonexistent.component") :throws clojure.lang.ExceptionInfo)

(tests
 (install-fixture!)

 "discover-component: the search-path scan is cached, and a second call does NOT
  re-scan (the private finders are never touched again)"
 (cache/clear!)
 (do (discover-component "fixture.widget")
     (with-redefs-fn {#'discovery/find-component-ns (spy/spy @#'discovery/find-component-ns)
                      #'discovery/find-controller-ns (spy/spy @#'discovery/find-controller-ns)}
       (fn []
         (let [result (discover-component "fixture.widget")]
           [(var? (:machine result))
            (fn? (deref (:component-fn result)))
            (spy/call-count @#'discovery/find-component-ns)
            (spy/call-count @#'discovery/find-controller-ns)
            (cache/cached-count)]))))
 := [true true 0 0 1]

 "discover-component: the cache key is [component controller], and a keyword
  ns-path keys the same entry as the equivalent string"
 (cache/clear!)
 (do (discover-component :fixture.widget)
     (discover-component "fixture.widget")
     (cache/cached-keys))
 := #{["fixture.widget" nil]}

 "discover-component: vars are resolved fresh on every call, so a REPL redef of
  the component fn is seen through a cache hit (and restored afterwards)"
 (cache/clear!)
 (let [component-fn-var (:component-fn (discover-component "fixture.widget"))
       original @component-fn-var
       during (with-redefs-fn {component-fn-var (fn [_ctx] [:div "redefined"])}
                (fn [] (@(:component-fn (discover-component "fixture.widget")) {})))]
   [during (identical? original @(:component-fn (discover-component "fixture.widget")))])
 := [[:div "redefined"] true]

 "discover-component: a failed scan caches nothing, so a later fix is picked up"
 (cache/clear!)
 (do (try (discover-component "nonexistent.component") (catch clojure.lang.ExceptionInfo _ nil))
     (cache/cached-count))
 := 0

 "set-component-search-paths! clears the cache — cached entries record which
  search path won, and a new order can resolve the same name elsewhere"
 (let [original @!search-paths]
   (discover-component "fixture.widget")
   (let [before (cache/cached-count)]
     (set-component-search-paths! original)
     [(pos? before) (cache/cached-count)]))
 := [true 0])

(tests
 (install-fixture!)

 "warm-cache!: reports what it loaded and leaves those entries cached"
 (cache/clear!)
 (let [report (warm-cache! ["fixture.widget"])]
   [(:warmed report) (:failed report) (number? (:elapsed-ms report)) (cache/cached-count)])
 := [1 [] true 1]

 "warm-cache!: a broken component lands in :failed instead of throwing, and the
  rest of the list still warms"
 (cache/clear!)
 (let [report (warm-cache! ["nonexistent.component" "fixture.widget"])]
   [(:warmed report)
    (mapv :component (:failed report))
    (string? (:error (first (:failed report))))
    (cache/cached-count)])
 := [1 ["nonexistent.component"] true 1]

 "warm-cache!: a [component controller] pair warms under the pair key"
 (cache/clear!)
 (do (warm-cache! [["fixture.widget" "widget-controller"]])
     (cache/cached-keys))
 := #{["fixture.widget" "widget-controller"]}

 "warm-cache!: an empty list is a no-op"
 (cache/clear!)
 (do (warm-cache! []) [(:warmed (warm-cache! [])) (cache/cached-count)])
 := [0 0])
