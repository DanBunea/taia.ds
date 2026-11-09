(ns taia.ds.datastar.adapters.handlers.discovery
  (:require
   [clojure.string :as str]
   [taia.ds.datastar.adapters.handlers.discovery-cache :as cache]
   [hyperfiddle.rcf :refer [tests]]
   [spy.core :as spy]))

(def ^:private !component-search-paths
  (atom ["taia.ds.datastar.adapters.components"]))

(defn set-component-search-paths!
  "Sets the ordered list of namespace prefixes used to resolve components.
   Call once at startup with the value from app-config.
   Example: [\"taia.ds.datastar.adapters.components\" \"my.app.components\"]"
  [paths]
  (reset! !component-search-paths paths)
  ;; Cached resolutions record WHICH search path won; a new path order can pick
  ;; a different namespace for the same component name.
  (cache/clear!))

(defn- try-require-ns
  "Tries to require a namespace, returns the namespace symbol if successful, nil otherwise.
   Logs real compilation/load errors but silently skips missing namespaces
   (FileNotFoundException is expected when searching multiple paths)."
  [ns-sym]
  (try
    (require ns-sym)
    (the-ns ns-sym)
    (catch java.io.FileNotFoundException _
      nil)
    (catch Exception e
      (println (str "[discovery] Failed to load namespace " ns-sym ": " (.getMessage e)))
      (.printStackTrace e)
      nil)))

(defn- find-ns-across-search-paths
  "Tries each search path in order, returning the first namespace symbol that
   successfully requires. Returns nil if none found."
  [ns-path]
  (some (fn [base-ns]
          (let [ns-str (str base-ns "." ns-path)
                ns-sym (symbol ns-str)]
            (when (try-require-ns ns-sym)
              ns-sym)))
        @!component-search-paths))

(defn- find-component-ns
  "Finds the component namespace across all configured search paths.
   Takes a namespace path like 'counter.counter' or 'counter.counter-page'."
  [ns-path]
  (find-ns-across-search-paths ns-path))

(defn- find-controller-ns
  "Finds the controller namespace across all configured search paths.
   Takes a namespace path like 'counter.counter' or 'counter.counter-page'
   and optionally a controller-name (which should already include '-controller' suffix).
   If controller-name is provided, uses it; otherwise appends '-controller' to the last segment.
   Controller-name can be either:
   - Just the last segment (e.g., 'counter-page-controller') - will be combined with parent parts
   - Full path (e.g., 'counter.counter-page-controller') - will be used as-is"
  [ns-path controller-name]
  (let [path-parts (str/split ns-path #"\.")
        parent-parts (butlast path-parts)
        controller-name-to-use (or controller-name
                                   (str (last path-parts) "-controller"))
        controller-path (if (str/includes? controller-name-to-use ".")
                          controller-name-to-use
                          (if (seq parent-parts)
                            (str (str/join "." parent-parts) "." controller-name-to-use)
                            controller-name-to-use))]
    (find-ns-across-search-paths controller-path)))

(defn- get-machine-var
  "Gets the machine var from a controller namespace.
   The machine is always at {controller-ns}/machine"
  [controller-ns]
  (when controller-ns
    (try
      (require controller-ns)
      (let [machine-sym (symbol (str controller-ns) "machine")]
        (or (when-let [machine-var (resolve machine-sym)]
              machine-var)
            (do (println (str "[discovery] machine var not found in " controller-ns))
                nil)))
      (catch Exception e
        (println (str "[discovery] Exception loading controller " controller-ns ": " (.getMessage e)))
        (.printStackTrace e)
        nil))))

(defn- get-component-fn-var
  "Gets the main component function from a component namespace.
   Takes the function name (last part of the namespace path)."
  [component-ns fn-name]
  (when component-ns
    (try
      (require component-ns)
      (let [fn-sym (symbol (str component-ns) fn-name)
            fn-var (resolve fn-sym)]
        (when fn-var
          fn-var))
      (catch Exception _
        nil))))

(defn- resolve-namespaces*
  "The SCAN half of discovery: walks the configured search paths to find the
   component and controller namespaces for `ns-path`. This is the expensive,
   cacheable part — a miss on a search path costs a caught FileNotFoundException.

   Returns {:controller-ns sym :component-ns sym :fn-name \"chat\"}; either
   namespace is nil when no search path held it (the caller turns that into the
   descriptive exception)."
  [ns-path controller-name]
  {:controller-ns (find-controller-ns ns-path controller-name)
   :component-ns (find-component-ns ns-path)
   :fn-name (last (str/split ns-path #"\."))})

(defn- complete?
  "Only a resolution that found BOTH namespaces is worth caching."
  [{:keys [controller-ns component-ns]}]
  (boolean (and controller-ns component-ns)))

(defn discover-component
  "Discovers a component by namespace path, searching all configured search paths in order.

   Takes a namespace path like:
   - \"counter.counter\" -> tries taia.ds.datastar.adapters.components.counter.counter, then my.app.components.counter.counter, etc.
   - \"counter.counter-page\" -> similarly tries all configured prefixes

   Optional controller-name parameter (should include '-controller' suffix):
   - If provided, uses that controller name instead of default
   - If nil, uses default behavior (appends '-controller' to component name)

   The search-path scan is cached per [ns-path controller-name] (see
   `taia.ds.datastar.adapters.handlers.discovery-cache`); the vars are resolved fresh on every
   call, so a REPL redefinition is always picked up.

   Returns map with:
   - :machine - the machine var from controller namespace
   - :component-fn - the component function var

   Throws exception with descriptive message listing all searched paths if component cannot be found."
  ([ns-path-keyword]
   (discover-component ns-path-keyword nil))
  ([ns-path-keyword controller-name]
   (let [ns-path (name ns-path-keyword)
         cache-key [ns-path controller-name]
         hit? (some? (cache/cached cache-key))
         {:keys [controller-ns component-ns fn-name]}
         (cache/get-or-resolve! cache-key
                                #(resolve-namespaces* ns-path controller-name)
                                complete?)
         machine-var (get-machine-var controller-ns)
         component-fn-var (get-component-fn-var component-ns fn-name)
         searched-paths @!component-search-paths]
     (cond
       (and machine-var component-fn-var)
       {:machine machine-var
        :component-fn component-fn-var}

       ;; A cached scan whose namespaces no longer yield their vars (a var
       ;; renamed or removed in the REPL): drop the entry and resolve once more
       ;; from scratch rather than serving a permanently broken cache.
       hit?
       (do (cache/evict! cache-key)
           (discover-component ns-path-keyword controller-name))

       :else
       (do
         (when-not machine-var
           (throw (ex-info (str "Machine not found for component: " ns-path
                                ". Searched paths: " (str/join ", " searched-paths))
                           {:ns-path ns-path
                            :controller-ns controller-ns
                            :searched-paths searched-paths})))
         (when-not component-ns
           (throw (ex-info (str "Component namespace not found: " ns-path
                                ". Searched paths: " (str/join ", " searched-paths))
                           {:ns-path ns-path
                            :searched-paths searched-paths})))
         (when-not component-fn-var
           (throw (ex-info (str "Component function not found in namespace: " component-ns
                                ". Searched paths: " (str/join ", " searched-paths))
                           {:ns-path ns-path
                            :component-ns component-ns
                            :searched-paths searched-paths}))))))))

(defn warm-cache!
  "Eagerly resolves `component-names` into the discovery cache so the first HTTP
   request for a component does not pay the namespace-load cost on the request
   thread (measured at 3.7-6.1s per component tree in `bb dev`).

   Each entry is either a component name (\"chat.chat\") or a
   [component-name controller-name] pair for a non-default controller.

   NEVER THROWS: a component that fails to resolve is collected into :failed and
   the rest keep warming. Returns
   {:warmed n :failed [{:component .. :controller .. :error msg}] :elapsed-ms n}.

   Callers usually run this on a background thread at startup; call it
   inline instead if you need every component loaded before the first request."
  [component-names]
  (let [start (System/currentTimeMillis)
        report (reduce
                (fn [acc entry]
                  (let [[component controller] (if (sequential? entry) entry [entry nil])]
                    (try
                      (discover-component component controller)
                      (update acc :warmed inc)
                      (catch Throwable t
                        (println (str "[discovery] warm-cache! failed for " component ": " (.getMessage t)))
                        (update acc :failed conj {:component component
                                                  :controller controller
                                                  :error (.getMessage t)})))))
                {:warmed 0 :failed []}
                component-names)]
    (assoc report :elapsed-ms (- (System/currentTimeMillis) start))))

(tests
 ;; Test 1: successful discovery via first search path
 (let [result (discover-component "counter.counter")]
   (var? (:machine result)) := true
   (fn? (deref (:component-fn result))) := true)

 ;; Test 2: successful discovery with counter-page
 (let [result (discover-component "counter.counter-page")]
   (var? (:machine result)) := true
   (fn? (deref (:component-fn result))) := true)

 ;; Test 3: discovery falls through to a later search path
 (let [original @!component-search-paths]
   (set-component-search-paths! ["taia.ds.nonexistent.components" "taia.ds.datastar.adapters.components"])
   (let [result (discover-component "counter.counter")]
     (var? (:machine result)) := true
     (fn? (deref (:component-fn result))) := true)
   (set-component-search-paths! original))

 ;; Test 4: throws with descriptive message when namespace not found in any path
 (try
   (discover-component "nonexistent.component")
   :no-throw
   (catch clojure.lang.ExceptionInfo e
     (let [msg (.getMessage e)
           data (ex-data e)]
       (clojure.string/includes? msg "Searched paths:") := true
       (contains? data :searched-paths) := true)))

 ;; Test 8: throws ExceptionInfo when not found
 (discover-component "nonexistent.component") :throws clojure.lang.ExceptionInfo)

(tests
 "discover-component: the search-path scan is cached, and a second call does NOT
  re-scan (the private finders are never touched again)"
 (cache/clear!)
 (do (discover-component "counter.counter")
     (with-redefs [find-component-ns (spy/spy find-component-ns)
                   find-controller-ns (spy/spy find-controller-ns)]
       (let [result (discover-component "counter.counter")]
         [(var? (:machine result))
          (fn? (deref (:component-fn result)))
          (spy/call-count find-component-ns)
          (spy/call-count find-controller-ns)
          (cache/cached-count)])))
 := [true true 0 0 1]

 "discover-component: the cache key is [component controller], and a keyword
  ns-path keys the same entry as the equivalent string"
 (cache/clear!)
 (do (discover-component :counter.counter)
     (discover-component "counter.counter")
     (cache/cached-keys))
 := #{["counter.counter" nil]}

 "discover-component: vars are resolved fresh on every call, so a REPL redef of
  the component fn is seen through a cache hit (and restored afterwards)"
 (cache/clear!)
 (let [component-fn-var (:component-fn (discover-component "counter.counter"))
       original @component-fn-var
       during (with-redefs-fn {component-fn-var (fn [_ctx] [:div "redefined"])}
                (fn [] (@(:component-fn (discover-component "counter.counter")) {})))]
   [during (identical? original @(:component-fn (discover-component "counter.counter")))])
 := [[:div "redefined"] true]

 "discover-component: a failed scan caches nothing, so a later fix is picked up"
 (cache/clear!)
 (do (try (discover-component "nonexistent.component") (catch clojure.lang.ExceptionInfo _ nil))
     (cache/cached-count))
 := 0

 "set-component-search-paths! clears the cache — cached entries record which
  search path won, and a new order can resolve the same name elsewhere"
 (let [original @!component-search-paths]
   (discover-component "counter.counter")
   (let [before (cache/cached-count)]
     (set-component-search-paths! original)
     [(pos? before) (cache/cached-count)]))
 := [true 0])

(tests
 "warm-cache!: reports what it loaded and leaves those entries cached"
 (cache/clear!)
 (let [report (warm-cache! ["counter.counter" "counter.counter-page"])]
   [(:warmed report) (:failed report) (number? (:elapsed-ms report)) (cache/cached-count)])
 := [2 [] true 2]

 "warm-cache!: a broken component lands in :failed instead of throwing, and the
  rest of the list still warms"
 (cache/clear!)
 (let [report (warm-cache! ["nonexistent.component" "counter.counter"])]
   [(:warmed report)
    (mapv :component (:failed report))
    (string? (:error (first (:failed report))))
    (cache/cached-count)])
 := [1 ["nonexistent.component"] true 1]

 "warm-cache!: a [component controller] pair warms under the pair key"
 (cache/clear!)
 (do (warm-cache! [["counter.counter" "counter-controller"]])
     (cache/cached-keys))
 := #{["counter.counter" "counter-controller"]}

 "warm-cache!: an empty list is a no-op"
 (cache/clear!)
 (do (warm-cache! []) [(:warmed (warm-cache! [])) (cache/cached-count)])
 := [0 0])

(comment

  (discover-component "counter.counter")
  (discover-component "counter.counter-page")
  (discover-component "counterx"))
