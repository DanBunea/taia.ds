(ns taia.ds.datastar.adapters.handlers.discovery-cache
  "Lookup cache for Datastar component resolution.

   WHAT IS CACHED: the resolved NAMESPACE SYMBOLS, never the vars.

   The expensive half of `discovery/discover-component` is the search-path scan:
   for \"chat.chat\" the prefix `taia.ds.datastar.adapters.components` does not hold it, so
   `require` throws a FileNotFoundException (stack trace and all) before the scan
   falls through to `my.app.components.chat.chat`. That answer never
   changes for a given [component-name controller-name] pair, so it is what we
   store. Turning the cached symbols back into vars is a namespace-map lookup
   with no I/O, and it is done on every hit — which keeps the cache REPL-safe:
   caching the vars themselves would survive an ordinary `defn` re-evaluation
   (same var object, new root binding) but go stale on `remove-ns` / `:reload-all`,
   which mint brand-new var objects.

   Deliberately NOT `cache.interface`: that component's contract is
   EDN-serializable values under a TTL, and it would add a cross-component
   dependency to the intentionally generic `datastar` component."
  (:require
   [hyperfiddle.rcf :refer [tests]]
   [spy.core :as spy]))

;; {[component-name controller-name] {:controller-ns sym :component-ns sym :fn-name "chat"}}
(def ^:private !cache (atom {}))

;; Misses are serialized on this monitor so a warm-up thread and a request
;; thread never `require` the same namespace tree at the same time.
(def ^:private resolve-lock (Object.))

(defn cached
  "The cached resolution for `k`, or nil when absent. nil is a NORMAL outcome."
  [k]
  (get @!cache k))

(defn store!
  "Stores `v` under `k`, overwriting. Returns `v`."
  [k v]
  (swap! !cache assoc k v)
  v)

(defn evict!
  "Removes the entry under `k`. Idempotent. Returns nil."
  [k]
  (swap! !cache dissoc k)
  nil)

(defn clear!
  "Removes every entry. Returns nil."
  []
  (reset! !cache {})
  nil)

(defn cached-count
  "How many resolutions are cached."
  []
  (count @!cache))

(defn cached-keys
  "The set of cached [component-name controller-name] keys."
  []
  (set (keys @!cache)))

(defn get-or-resolve!
  "Cache-aside. Returns the cached resolution for `k`, else calls `resolve-fn`
   (0-arity) and returns its result, storing it when `cacheable?` says so
   (default: any non-nil result).

   Hits take no lock; misses are serialized and re-check the cache inside the
   lock, so concurrent callers resolve a given component exactly once.

   FAILURES ARE NEVER CACHED: a nil result is not stored, an incomplete one is
   returned but not stored, and an exception from `resolve-fn` propagates with
   nothing written — the next call retries."
  ([k resolve-fn]
   (get-or-resolve! k resolve-fn (constantly true)))
  ([k resolve-fn cacheable?]
   (or (cached k)
       (locking resolve-lock
         (or (cached k)
             (when-let [v (resolve-fn)]
               (if (cacheable? v)
                 (store! k v)
                 v)))))))

(tests
 "get-or-resolve!: a miss calls resolve-fn and stores; the next call is a hit
  that does NOT call resolve-fn again"
 (clear!)
 (let [resolve-fn (spy/spy (fn [] {:controller-ns 'a.b-controller :component-ns 'a.b :fn-name "b"}))
       first-call (get-or-resolve! ["a.b" nil] resolve-fn)
       second-call (get-or-resolve! ["a.b" nil] resolve-fn)]
   [first-call
    (= first-call second-call)
    (spy/call-count resolve-fn)
    (cached-count)])
 := [{:controller-ns 'a.b-controller :component-ns 'a.b :fn-name "b"} true 1 1]

 "get-or-resolve!: the controller-name is part of the key — two controllers for
  the same component do not collide"
 (clear!)
 (do (get-or-resolve! ["a.b" nil] (fn [] {:component-ns 'a.b}))
     (get-or-resolve! ["a.b" "other-controller"] (fn [] {:component-ns 'a.b}))
     (cached-keys))
 := #{["a.b" nil] ["a.b" "other-controller"]}

 "get-or-resolve!: an incomplete resolution is returned but NOT stored, so the
  next call resolves again"
 (clear!)
 (let [resolve-fn (spy/spy (fn [] {:controller-ns nil :component-ns 'a.b}))
       complete? (fn [{:keys [controller-ns component-ns]}] (and controller-ns component-ns))
       out (get-or-resolve! ["a.b" nil] resolve-fn complete?)]
   (get-or-resolve! ["a.b" nil] resolve-fn complete?)
   [out (cached-count) (spy/call-count resolve-fn)])
 := [{:controller-ns nil :component-ns 'a.b} 0 2]

 "get-or-resolve!: a nil resolution is not stored"
 (clear!)
 [(get-or-resolve! ["missing" nil] (constantly nil)) (cached-count)] := [nil 0]

 "get-or-resolve!: a throwing resolve-fn caches nothing and rethrows"
 (clear!)
 (try
   (get-or-resolve! ["boom" nil] (fn [] (throw (ex-info "nope" {}))))
   :no-throw
   (catch clojure.lang.ExceptionInfo _ (cached-count)))
 := 0

 "evict! removes one entry, clear! removes them all"
 (clear!)
 (do (store! ["a.b" nil] {:component-ns 'a.b})
     (store! ["c.d" nil] {:component-ns 'c.d})
     (evict! ["a.b" nil])
     [(cached ["a.b" nil]) (cached-count) (do (clear!) (cached-count))])
 := [nil 1 0])
