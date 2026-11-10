(ns taia.ds.datastar.adapters.handlers.discovery
  (:require
   [clojure.string :as str]
   [taia.ds.datastar.adapters.handlers.discovery-cache :as cache]))

(def ^:private !component-search-paths
  "Namespace prefixes components are resolved under. Empty until the
   application calls `set-component-search-paths!`; nothing in the library
   itself is a component."
  (atom []))

(defn set-component-search-paths!
  "Sets the ordered list of namespace prefixes used to resolve components.
   Call once at startup with the value from app-config.
   Example: [\"my.app.components\" \"my.app.admin.components\"]"
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
   - \"chat.chat\" -> tries my.app.components.chat.chat, then the next prefix, etc.
   - \"chat.chat-page\" -> similarly tries all configured prefixes

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

