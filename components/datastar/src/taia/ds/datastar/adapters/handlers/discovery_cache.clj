(ns taia.ds.datastar.adapters.handlers.discovery-cache
  "Lookup cache for Datastar component resolution.

   WHAT IS CACHED: the resolved NAMESPACE SYMBOLS, never the vars.

   The expensive half of `discovery/discover-component` is the search-path scan:
   for \"chat.chat\" an earlier prefix such as `my.app.admin.components` does not hold it, so
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

;;; Tests — the accessors. Each case starts from its own cache
;;; (`(with-redefs [!cache (atom …)] …)`), never the global one.

(tests
 "cached: the resolution under k"
 (with-redefs [!cache (atom {["a.b" nil] {:controller-ns 'a.b-controller :component-ns 'a.b :fn-name "b"}})]
   (cached ["a.b" nil]) := {:controller-ns 'a.b-controller :component-ns 'a.b :fn-name "b"})

 "cached: absent → nil (a normal outcome); the controller-name is part of the key"
 (with-redefs [!cache (atom {["a.b" nil] {:controller-ns 'a.b-controller :component-ns 'a.b :fn-name "b"}})]
   (cached ["a.b" "other-controller"]) := nil)

 "store!: stores v under k and returns it — two controllers for the same
  component do not collide"
 (with-redefs [!cache (atom {["a.b" nil] {:controller-ns 'a.b-controller :component-ns 'a.b :fn-name "b"}})]
   (store! ["a.b" "other-controller"] {:controller-ns 'c.d-controller :component-ns 'c.d :fn-name "d"}) := {:controller-ns 'c.d-controller :component-ns 'c.d :fn-name "d"}
   @!cache := {["a.b" nil]              {:controller-ns 'a.b-controller :component-ns 'a.b :fn-name "b"}
               ["a.b" "other-controller"] {:controller-ns 'c.d-controller :component-ns 'c.d :fn-name "d"}})

 "store!: overwrites the entry under k"
 (with-redefs [!cache (atom {["a.b" nil] {:controller-ns 'a.b-controller :component-ns 'a.b :fn-name "b"}})]
   (store! ["a.b" nil] {:controller-ns 'c.d-controller :component-ns 'c.d :fn-name "d"}) := {:controller-ns 'c.d-controller :component-ns 'c.d :fn-name "d"}
   @!cache := {["a.b" nil] {:controller-ns 'c.d-controller :component-ns 'c.d :fn-name "d"}})

 "evict!: removes one entry, returns nil"
 (with-redefs [!cache (atom {["a.b" nil] {:controller-ns 'a.b-controller :component-ns 'a.b :fn-name "b"}
                             ["c.d" nil] {:controller-ns 'c.d-controller :component-ns 'c.d :fn-name "d"}})]
   (evict! ["a.b" nil]) := nil
   @!cache := {["c.d" nil] {:controller-ns 'c.d-controller :component-ns 'c.d :fn-name "d"}})

 "evict!: an absent key is a no-op (idempotent)"
 (with-redefs [!cache (atom {["a.b" nil] {:controller-ns 'a.b-controller :component-ns 'a.b :fn-name "b"}})]
   (evict! ["c.d" nil]) := nil
   @!cache := {["a.b" nil] {:controller-ns 'a.b-controller :component-ns 'a.b :fn-name "b"}})

 "clear!: removes every entry, returns nil"
 (with-redefs [!cache (atom {["a.b" nil] {:controller-ns 'a.b-controller :component-ns 'a.b :fn-name "b"}
                             ["c.d" nil] {:controller-ns 'c.d-controller :component-ns 'c.d :fn-name "d"}})]
   (clear!) := nil
   @!cache := {})

 "cached-count / cached-keys: how many, and which keys"
 (with-redefs [!cache (atom {["a.b" nil] {:controller-ns 'a.b-controller :component-ns 'a.b :fn-name "b"}
                             ["c.d" nil] {:controller-ns 'c.d-controller :component-ns 'c.d :fn-name "d"}})]
   (cached-count) := 2
   (cached-keys) := #{["a.b" nil] ["c.d" nil]})

 "cached-count / cached-keys: an empty cache"
 (with-redefs [!cache (atom {})]
   (cached-count) := 0
   (cached-keys) := #{}))

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

;;; Tests — get-or-resolve!: cached and store! are spies (tested above).

(tests
 "get-or-resolve!: a hit returns the cached resolution — resolve-fn is not
  called, nothing is stored, no lock is taken (one lookup)"
 (with-redefs [cached (spy/spy (constantly {:controller-ns 'a.b-controller :component-ns 'a.b :fn-name "b"}))
               store! (spy/spy)]
   (let [resolve-fn (spy/spy)]
     (get-or-resolve! ["a.b" nil] resolve-fn) := {:controller-ns 'a.b-controller :component-ns 'a.b :fn-name "b"}
     (spy/called-once-with? cached ["a.b" nil]) := true
     (spy/not-called? resolve-fn) := true
     (spy/not-called? store!) := true))

 "get-or-resolve!: a miss looks again inside the lock, calls resolve-fn and
  stores its result, which it returns"
 (with-redefs [cached (spy/spy (constantly nil))
               store! (spy/spy (fn [_ v] v))]
   (let [resolve-fn (spy/spy (constantly {:controller-ns 'a.b-controller :component-ns 'a.b :fn-name "b"}))]
     (get-or-resolve! ["a.b" nil] resolve-fn) := {:controller-ns 'a.b-controller :component-ns 'a.b :fn-name "b"}
     (spy/calls cached) := [[["a.b" nil]] [["a.b" nil]]]
     (spy/called-once? resolve-fn) := true
     (spy/called-once-with? store! ["a.b" nil] {:controller-ns 'a.b-controller :component-ns 'a.b :fn-name "b"}) := true))

 "get-or-resolve!: a resolution cacheable? rejects is returned but NOT stored"
 (with-redefs [cached (spy/spy (constantly nil))
               store! (spy/spy)]
   (get-or-resolve! ["a.b" nil]
                    (constantly {:controller-ns nil :component-ns 'a.b})
                    (fn [{:keys [controller-ns component-ns]}] (and controller-ns component-ns)))
   := {:controller-ns nil :component-ns 'a.b}
   (spy/not-called? store!) := true)

 "get-or-resolve!: a nil resolution is not stored"
 (with-redefs [cached (spy/spy (constantly nil))
               store! (spy/spy)]
   (get-or-resolve! ["missing" nil] (constantly nil)) := nil
   (spy/not-called? store!) := true)

 "get-or-resolve!: a throwing resolve-fn stores nothing and rethrows"
 (with-redefs [cached (spy/spy (constantly nil))
               store! (spy/spy)]
   (get-or-resolve! ["boom" nil] (fn [] (throw (ex-info "nope" {}))))
   :throws clojure.lang.ExceptionInfo
   (spy/not-called? store!) := true)

 ;; End-to-end with a real (fresh) cache: the cache-aside round trip discovery
 ;; relies on — the point of these two is the SECOND call.

 "round trip: a miss stores, so the next call is a hit that does NOT call
  resolve-fn again"
 (with-redefs [!cache (atom {})]
   (let [resolve-fn (spy/spy (constantly {:controller-ns 'a.b-controller :component-ns 'a.b :fn-name "b"}))]
     (get-or-resolve! ["a.b" nil] resolve-fn) := {:controller-ns 'a.b-controller :component-ns 'a.b :fn-name "b"}
     (get-or-resolve! ["a.b" nil] resolve-fn) := {:controller-ns 'a.b-controller :component-ns 'a.b :fn-name "b"}
     (spy/call-count resolve-fn) := 1
     @!cache := {["a.b" nil] {:controller-ns 'a.b-controller :component-ns 'a.b :fn-name "b"}}))

 "round trip: an incomplete resolution is not stored, so the next call
  resolves again"
 (with-redefs [!cache (atom {})]
   (let [resolve-fn (spy/spy (constantly {:controller-ns nil :component-ns 'a.b}))]
     (get-or-resolve! ["a.b" nil]
                      resolve-fn
                      (fn [{:keys [controller-ns component-ns]}] (and controller-ns component-ns)))
     := {:controller-ns nil :component-ns 'a.b}
     (get-or-resolve! ["a.b" nil]
                      resolve-fn
                      (fn [{:keys [controller-ns component-ns]}] (and controller-ns component-ns)))
     := {:controller-ns nil :component-ns 'a.b}
     (spy/call-count resolve-fn) := 2
     @!cache := {})))
