(ns taia.ds.logging.interface
  (:require
   [hyperfiddle.rcf :refer [tests]]
   #?(:clj [clojure.core :as core])
   #?(:clj [com.brunobonacci.mulog :as u])))

#?(:clj (def ^:private levels
          "Log-level ordering (ascending): :verbose < :debug < :info < :warn."
          {:verbose 0 :debug 1 :info 2 :warn 3}))

#?(:clj (def ^:private !log-level (atom :info)))

(comment
  (reset! !log-level :verbose)

  nil)

#?(:clj
   (defn set-log-level!
     "Sets the global log level. Called once at startup from config.
      Levels (ascending): :verbose < :debug < :info < :warn."
     [level]
     (reset! !log-level level)))

#?(:clj
   (defn log-level
     "Returns the current global log level."
     []
     @!log-level))

#?(:clj
   (defn trace-enabled?
     "Returns true when trace at level should be emitted given min-level.
      Levels (ascending): :verbose < :debug < :info < :warn."
     [level min-level]
     (>= (get levels level 0) (get levels min-level 2))))

#?(:clj
   (defmacro with-context
     "Macro wrapper for mulog/with-context.
      Usage: (with-context ctx-map & body)
      Example: (with-context {:mulog/trace-id \"123\"} (trace ...))"
     {:style/indent 1}
     [ctx & body]
     `(u/with-context ~ctx ~@body)))

#?(:clj
   (defmacro trace-when
     "When condition is true, runs (u/trace event-name pairs body).
      Otherwise runs body only. The body is always executed; only the tracing is conditional.
      Usage: (trace-when condition event-name [k1 v1 ...] body)
      Example: (trace-when (trace-enabled? :debug min-level) \"llm/event\" [...] (do-something))"
     {:style/indent 3}
     [condition event-name pairs & body]
     `(if ~condition
        (u/trace ~event-name ~pairs ~@body)
        (do ~@body))))

#?(:clj
   (defmacro trace
     "Always runs (u/trace event-name pairs body).
      Same as (trace-when true event-name pairs body).
      Usage: (trace event-name [k1 v1 ...] body)"
     {:style/indent 2}
     [event-name pairs & body]
     `(trace-when true ~event-name ~pairs ~@body)))

#?(:clj
   (defmacro warn
     "Emits a discrete mulog event at warn severity. Unlike `trace`, this is
      a fire-and-forget event with no body — used for refusal/conflict
      branches that don't wrap a computation. `pairs` is a flat bracket-vector
      of k/v pairs (same shape as `trace`'s pairs arg) for visual symmetry.
      Usage: (warn event-name [k1 v1 k2 v2 ...])"
     {:style/indent 1}
     [event-name pairs]
     `(u/log ~event-name ~@pairs)))

#?(:clj
   (defn local-context
     "Function wrapper for mulog/local-context.
      Returns the current mulog context map."
     []
     (u/local-context)))

#?(:clj
   (defn flatten-ctx
     "Returns a mulog context that re-parents a span directly under the turn's
      root span, by pointing `:mulog/parent-trace` at the shared
      `:mulog/root-trace`. Wrap a span in `(with-context (flatten-ctx ctx) ...)`
      to render sibling steps as a flat list instead of each nesting under the
      previous one. Returns `ctx` unchanged when it carries no root-trace."
     [ctx]
     (if-let [r (:mulog/root-trace ctx)]
       {:mulog/root-trace r :mulog/parent-trace r}
       ctx)))

(def ^:private heavy-keys
  "Keys whose values are too large/opaque to log (Datomic db/conn snapshots,
   transaction results, etc.). Stripped from maps by `safe-for-log`."
  #{:db :conn :!conn :old-db :db-after :db-before :db-snapshot :tx-result})

(def ^:private safe-for-log-max-chars
  "Hard cap on the string length returned by `safe-for-log`. Keeps the
   serialized payload well under any tag-value limit imposed by Zipkin /
   OTel collectors (typically 64KB; many gateways drop spans whose tags
   exceed lower thresholds). 8000 chars is plenty to capture realistic
   ctx maps after `safe-for-log`'s structural shrinking."
  8000)

(declare ^:private sanitize)

(defn- sanitize-map
  [m]
  (let [dropped (filterv #(contains? heavy-keys %) (keys m))
        kept    (into {}
                      (keep (fn [[k v]]
                              (when-not (contains? heavy-keys k)
                                [k (sanitize v)])))
                      m)]
    (cond-> kept
      (seq dropped) (assoc :_dropped (mapv str dropped)))))

(defn- sanitize-coll
  [xs]
  (let [head (mapv sanitize (take 3 xs))]
    (if (> (bounded-count 4 xs) 3)
      (conj head "...")
      head)))

(defn- sanitize
  "Recursively shrinks v: drops heavy map keys, truncates collections >3,
   stringifies keywords/dates/unknown ref types. Returns a Clojure data
   structure (no string conversion yet — see `safe-for-log`)."
  [v]
  (cond
    (nil? v)        nil
    (map? v)        (sanitize-map v)
    (set? v)        (sanitize-coll v)
    (sequential? v) (sanitize-coll v)
    (keyword? v)    (str v)
    (string? v)     v
    (boolean? v)    v
    (number? v)     v
    :else           (pr-str v)))

(defn- truncate-str
  [^String s max-chars]
  (if (> (count s) max-chars)
    (str (subs s 0 max-chars) "…<truncated>")
    s))

(defn safe-for-log
  "Returns a single log-safe **string** representation of v, suitable as
   a tag value for Zipkin / OTel publishers without truncation surprises.
   Pipeline:
   1. Recursively sanitize: drop heavy keys (`:db`, `:conn`, `:!conn`,
      `:old-db`, `:db-after`, `:db-before`, `:db-snapshot`, `:tx-result`);
      truncate sequential / set collections > 3 elements with `\"...\"`;
      stringify keywords/dates/unknown ref types; record dropped keys
      under `:_dropped`.
   2. `pr-str` the result (Clojure-EDN-encoded, JSON-embeddable as a
      string value).
   3. Hard-cap to ~8000 chars with a `…<truncated>` suffix so individual
      events never exceed downstream tag-value limits."
  [v]
  (-> v sanitize pr-str (truncate-str safe-for-log-max-chars)))

(tests
 "safe-for-log: always returns a string"
 (string? (safe-for-log nil))                := true
 (string? (safe-for-log "hi"))               := true
 (string? (safe-for-log 42))                 := true
 (string? (safe-for-log {:user "u"}))        := true
 (string? (safe-for-log [1 2 3 4 5]))        := true
 (string? (safe-for-log #{1 2 3 4 5}))       := true

 "safe-for-log: encodes scalars via pr-str"
 (safe-for-log nil)   := "nil"
 (safe-for-log "hi")  := "\"hi\""
 (safe-for-log 42)    := "42"
 (safe-for-log true)  := "true"
 (safe-for-log :foo/bar) := "\":foo/bar\""

 "safe-for-log: drops heavy keys and records :_dropped in the encoded map"
 (let [s (safe-for-log {:db :A :conn :B :user "u"})]
   (clojure.string/includes? s ":user \"u\"")  := true
   (clojure.string/includes? s ":_dropped")    := true
   (clojure.string/includes? s "\":db\"")      := true        ;; only inside :_dropped vec
   (clojure.string/includes? s "\":conn\"")    := true
   ;; heavy values themselves are gone
   (clojure.string/includes? s ":A")           := false
   (clojure.string/includes? s ":B")           := false)

 "safe-for-log: omits :_dropped when nothing was dropped"
 (re-find #":_dropped" (safe-for-log {:user "u"})) := nil

 "safe-for-log: truncates collections > 3 elements"
 (clojure.string/includes? (safe-for-log [1 2 3 4 5]) "\"...\"") := true

 "safe-for-log: handles lazy seqs without realizing them"
 (string? (safe-for-log (range 1000))) := true

 "safe-for-log: hard-caps oversized payloads"
 (let [big (apply str (repeat 20000 "x"))
       out (safe-for-log {:big big})]
   (<= (count out) (+ safe-for-log-max-chars 32))           := true
   (boolean (re-find #"…<truncated>" out))                  := true))
