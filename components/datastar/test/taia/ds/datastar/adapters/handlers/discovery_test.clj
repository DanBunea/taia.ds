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

(defn- fixture-vars
  "What discovery resolves the fixture to: its machine and render-fn vars."
  []
  {:machine      (ns-resolve controller-ns 'machine)
   :component-fn (ns-resolve component-ns 'widget)})

(defn- thrown
  "The message and data of the ex-info `(f)` throws, else nil."
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      {:message (ex-message e) :data (ex-data e)})))

(defn- render-widget
  "Renders the fixture through discovery, the way an event does."
  []
  (-> "fixture.widget"
      discover-component
      :component-fn
      deref
      (apply [{}])))

;;; discover-component: end to end against the in-memory fixture — resolving
;;; namespaces by convention (and caching that scan) IS what it does.

(tests
 (install-fixture!)

 "discover-component: found via the search path → the machine and render-fn vars"
 (cache/clear!)
 (discover-component "fixture.widget") := (fixture-vars)

 "discover-component: falls through to a later search path"
 (set-component-search-paths! ["taia.ds.nonexistent.components" "taia.ds.datastar.test-fixture"]) ; clears the cache too
 (discover-component "fixture.widget") := (fixture-vars)
 (install-fixture!)

 "discover-component: nothing matches → an ex-info naming the searched paths"
 (cache/clear!)
 (thrown #(discover-component "nonexistent.component"))
 := {:message "Machine not found for component: nonexistent.component. Searched paths: taia.ds.datastar.test-fixture"
     :data    {:ns-path "nonexistent.component" :controller-ns nil :searched-paths ["taia.ds.datastar.test-fixture"]}}
 (discover-component "nonexistent.component") :throws clojure.lang.ExceptionInfo)

(tests
 (install-fixture!)

 "discover-component: a cache hit does NOT re-scan (the private finders are
  never called)"
 (cache/clear!)
 (discover-component "fixture.widget")                    ; the miss that caches it
 (let [find-component-ns  (spy/spy)
       find-controller-ns (spy/spy)]
   (with-redefs [discovery/find-component-ns  find-component-ns
                 discovery/find-controller-ns find-controller-ns]
     (discover-component "fixture.widget") := (fixture-vars)
     (spy/not-called? find-component-ns) := true
     (spy/not-called? find-controller-ns) := true
     (cache/cached-count) := 1))

 "discover-component: a keyword ns-path keys the same entry as the
  equivalent string"
 (cache/clear!)
 (discover-component :fixture.widget)
 (discover-component "fixture.widget")
 (cache/cached-keys) := #{["fixture.widget" nil]}

 "discover-component: the cache key is [component controller]"
 (cache/clear!)
 (discover-component "fixture.widget" "widget-controller")
 (cache/cached-keys) := #{["fixture.widget" "widget-controller"]}

 "discover-component: vars are resolved fresh on every call, so a REPL redef
  of the render fn is seen through a cache hit (and its restore too)"
 (cache/clear!)
 (with-redefs-fn {(-> "fixture.widget"
                      discover-component
                      :component-fn)
                  (fn [_ctx] [:div "redefined"])}
   render-widget)
 := [:div "redefined"]
 (render-widget) := [:div nil]

 "discover-component: a failed scan caches nothing, so a later fix is picked up"
 (cache/clear!)
 (discover-component "nonexistent.component") :throws clojure.lang.ExceptionInfo
 (cache/cached-count) := 0)

(tests
 (install-fixture!)

 "set-component-search-paths!: sets the paths and clears the cache — cached
  entries record which search path won, and a new order can resolve the same
  name elsewhere"
 (with-redefs [cache/clear! (spy/spy)]
   (set-component-search-paths! ["taia.ds.other.components" "taia.ds.datastar.test-fixture"]) := nil
   @!search-paths := ["taia.ds.other.components" "taia.ds.datastar.test-fixture"]
   (spy/called-once? cache/clear!) := true)
 (install-fixture!))

;;; warm-cache!: discover-component is a spy (tested above).

(tests
 (install-fixture!)
 (defn- report [warm-result] (dissoc warm-result :elapsed-ms))

 "warm-cache!: discovers each component (default controller), and reports how
  many warmed"
 (with-redefs [discover-component (spy/spy)]
   (-> ["fixture.widget"]
       warm-cache!
       report)
   := {:warmed 1 :failed []}
   (spy/called-once-with? discover-component "fixture.widget" nil) := true)

 "warm-cache!: the report says how long it took, in ms"
 (with-redefs [discover-component (spy/spy)]
   (-> ["fixture.widget"]
       warm-cache!
       :elapsed-ms
       nat-int?))
 := true

 "warm-cache!: a broken component lands in :failed instead of throwing, and
  the rest of the list still warms"
 (with-redefs [discover-component (spy/spy (fn [component _controller]
                                             (when (= "nonexistent.component" component)
                                               (throw (ex-info "Machine not found" {})))))]
   (-> ["nonexistent.component" "fixture.widget"]
       warm-cache!
       report)
   := {:warmed 1
       :failed [{:component "nonexistent.component" :controller nil :error "Machine not found"}]}
   (spy/calls discover-component) := [["nonexistent.component" nil] ["fixture.widget" nil]])

 "warm-cache!: a [component controller] pair is discovered with that controller"
 (with-redefs [discover-component (spy/spy)]
   (warm-cache! [["fixture.widget" "widget-controller"]])
   (spy/called-once-with? discover-component "fixture.widget" "widget-controller") := true)

 "warm-cache!: an empty list is a no-op"
 (with-redefs [discover-component (spy/spy)]
   (-> []
       warm-cache!
       report)
   := {:warmed 0 :failed []}
   (spy/not-called? discover-component) := true)

 "end to end (the point of warm-cache!): the warmed components are left in
  the discovery cache"
 (cache/clear!)
 (warm-cache! ["fixture.widget"])
 (cache/cached-keys) := #{["fixture.widget" nil]})
