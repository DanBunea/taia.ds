(ns taia.ds.datastar.adapters.handlers.observer
  "Pluggable observation hook for the two Datastar entry points
   (/ds/nds and /ds/nds-connect). `datastar` stays metrics-agnostic: it
   reports plain maps to ONE observer fn installed at startup
   (`taia.ds.datastar.interface/set-observer!`), and does nothing at all when none is set.

   Observations:
   - {:type :event :route :nds|:nds-connect :component c :event e
      :outcome :ok|:error :seconds s}
   - {:type :sse-opened :component c}
   - {:type :sse-closed :component c :seconds lifetime}

   No component-id is ever reported — every instance of a component is one
   series. And the reported values are BOUNDED: component and event come
   from the URL, so anyone could mint new ones. A component counts only once
   discovery has resolved it (the discovery cache holds it), an event only
   when that component's machine declares it; everything else collapses to
   `unknown-component` / `other-event`."
  (:require
   [clojure.string :as str]
   [taia.ds.datastar.adapters.handlers.discovery :as discovery]
   [taia.ds.datastar.adapters.handlers.discovery-cache :as cache]
   [hyperfiddle.rcf :refer [tests]]
   [taia.ds.logging.interface :as log]
   [spy.core :as spy]))

(def unknown-component "_unknown")
(def other-event "_other")

(defonce ^:private !observer (atom nil))

(defn set-observer!
  "Installs `f` (a 1-arg fn of an observation map) as the observer; nil
   removes it. Returns nil."
  [f]
  (reset! !observer f)
  nil)

(defn- notify!
  "Hands `observation` to the observer. An observer failure is logged and
   swallowed — metrics must never fail a request."
  [observation]
  (when-let [f @!observer]
    (try
      (f observation)
      (catch Throwable e
        (log/warn ::observer-failed [:type (:type observation) :error (ex-message e)])))))

(defn- machine-events*
  "Every event a machine (or state node) declares in an `:on` map, at any
   depth, as the upper-case name the pipeline transitions on."
  [node]
  (into (if (map? (:on node)) (set (map name (keys (:on node)))) #{})
        (mapcat (comp machine-events* val))
        (:states node)))

(def ^:private machine-events
  ;; Keyed by the machine value: a REPL redefinition is a new key.
  (memoize machine-events*))

(defn- discovered-machine
  "The machine of a component discovery has ALREADY resolved, else nil.
   Cache-only on purpose: an unresolved name is attacker-controlled and
   must neither become a label nor trigger a second namespace scan."
  [component-name controller-name]
  (when (cache/cached [(name component-name) controller-name])
    (try
      @(:machine (discovery/discover-component component-name controller-name))
      (catch Throwable _ nil))))

(defn labels
  "Bounded {:component :event} label values for one component event."
  [component-name controller-name event-name]
  (if-let [machine (discovered-machine component-name controller-name)]
    {:component (name component-name)
     :event     (let [e (str/upper-case (str event-name))]
                  (if (contains? (machine-events machine) e) e other-event))}
    {:component unknown-component
     :event     other-event}))

(defn- seconds-since [start-nanos]
  (/ (- (System/nanoTime) start-nanos) 1e9))

(defn- error-response? [result]
  (and (map? result) (>= (or (:status result) 200) 400)))

(defn observe-event!
  "Calls `(f)` and returns its result. When an observer is set, reports the
   call's duration as an :event observation: :error when `f` throws
   (rethrown) or returns a Ring response with status >= 400, else :ok.
   Labels are computed AFTER `f`, so the discovery `f` performed counts."
  [route component-name controller-name event-name f]
  (if-not @!observer
    (f)
    (let [start    (System/nanoTime)
          !outcome (volatile! :error)]
      (try
        (let [result (f)]
          (when-not (error-response? result)
            (vreset! !outcome :ok))
          result)
        (finally
          (notify! (merge {:type    :event
                           :route   route
                           :outcome @!outcome
                           :seconds (seconds-since start)}
                          (labels component-name controller-name event-name))))))))

(defn connection-opened!
  "Reports an opened SSE stream. Returns the token `connection-closed!`
   needs (the label is fixed at open so the gauge's up and down always hit
   the same series), or nil when no observer is set."
  [component-name started-nanos]
  (when @!observer
    (let [token {:component (:component (labels component-name nil nil))
                 :started   started-nanos}]
      (notify! {:type :sse-opened :component (:component token)})
      token)))

(defn connection-closed!
  "Reports the close of a stream `connection-opened!` returned `token` for.
   nil token ⇒ no-op."
  [token]
  (when token
    (notify! {:type      :sse-closed
              :component (:component token)
              :seconds   (seconds-since (:started token))})))

(def ^:private test-machine
  {:id :t :initial :idle
   :on {:GLOBAL :idle}
   :states {:idle {:on {:INIT :idle :SEND {:target :busy}}}
            :busy {:states {:inner {:on {:DONE :idle}}}}}})

(tests
 "machine-events*: :on keys at every depth, as names"
 (machine-events* test-machine) := #{"GLOBAL" "INIT" "SEND" "DONE"}

 "labels: a component discovery never resolved → unknown/other, without
  calling discovery (the cache is the gate)"
 (with-redefs [cache/cached (constantly nil)
               discovery/discover-component (spy/spy)]
   (labels :evil.thing "c1" "WHATEVER")
   := {:component "_unknown" :event "_other"}
   (spy/not-called? discovery/discover-component) := true)

 "labels: resolved component — declared events (any case) keep their name,
  undeclared ones collapse to _other"
 (def test-machine-var (var test-machine))
 (with-redefs [cache/cached (constantly {:controller-ns 'x :component-ns 'y})
               discovery/discover-component (constantly {:machine test-machine-var})]
   (labels :chat.chat nil "init") := {:component "chat.chat" :event "INIT"}
   (labels :chat.chat nil "DONE") := {:component "chat.chat" :event "DONE"}
   (labels :chat.chat nil "RENDERER.foo") := {:component "chat.chat" :event "_other"})

 "observe-event!: no observer → just calls f. (These tests swap the global
  observer — RCF also runs in a live `bb dev` on ns load — so the installed
  one is restored at the end.)"
 (def prior-observer @!observer)
 (set-observer! nil)
 (observe-event! :nds :chat.chat nil "INIT" (constantly ::r)) := ::r

 "observe-event!: ok result → one :event observation with bounded labels"
 (def obs (spy/spy))
 (set-observer! obs)
 (with-redefs [labels (constantly {:component "chat.chat" :event "INIT"})]
   (observe-event! :nds :chat.chat nil "INIT" (constantly {:status 200})))
 := {:status 200}
 (dissoc (ffirst (spy/calls obs)) :seconds)
 := {:type :event :route :nds :outcome :ok :component "chat.chat" :event "INIT"}
 (number? (:seconds (ffirst (spy/calls obs)))) := true

 "observe-event!: a 4xx/5xx response is an :error"
 (def obs2 (spy/spy))
 (set-observer! obs2)
 (with-redefs [labels (constantly {:component "_unknown" :event "_other"})]
   (observe-event! :nds :nope nil "X" (constantly {:status 400})))
 (:outcome (ffirst (spy/calls obs2))) := :error

 "observe-event!: a throw is reported as :error and rethrown"
 (def obs3 (spy/spy))
 (set-observer! obs3)
 (with-redefs [labels (constantly {:component "home.home" :event "INIT"})]
   (try (observe-event! :nds-connect :home.home nil "INIT" #(throw (ex-info "boom" {})))
        (catch Exception e (ex-message e))))
 := "boom"
 (select-keys (ffirst (spy/calls obs3)) [:route :outcome]) := {:route :nds-connect :outcome :error}

 "observe-event!: a failing observer never fails the request"
 (set-observer! (fn [_] (throw (ex-info "observer down" {}))))
 (with-redefs [labels (constantly {:component "a" :event "B"})]
   (observe-event! :nds :a nil "B" (constantly ::still-served)))
 := ::still-served

 "connection-opened!/closed!: the same label on both sides, lifetime on close"
 (def obs4 (spy/spy))
 (set-observer! obs4)
 (def token (with-redefs [labels (constantly {:component "home.home" :event "_other"})]
              (connection-opened! :home.home (System/nanoTime))))
 (connection-closed! token)
 (mapv (comp #(dissoc % :seconds) first) (spy/calls obs4))
 := [{:type :sse-opened :component "home.home"}
     {:type :sse-closed :component "home.home"}]
 (number? (:seconds (first (second (spy/calls obs4))))) := true

 "connection-opened!: no observer → nil token, closed! on nil is a no-op"
 (set-observer! nil)
 (connection-opened! :home.home (System/nanoTime)) := nil
 (connection-closed! nil) := nil
 (set-observer! prior-observer))
