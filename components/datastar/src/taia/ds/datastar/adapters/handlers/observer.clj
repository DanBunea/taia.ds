(ns taia.ds.datastar.adapters.handlers.observer
  "Pluggable observation hook for the two Datastar entry points
   (/ds/nds and /ds/nds-connect). `datastar` stays metrics-agnostic: it
   reports plain maps to ONE observer fn installed at startup
   (`taia.ds.datastar.interface/set-observer!`), and does nothing at all when none is set.

   Observations:
   - {:type :event :route :nds|:nds-connect|:nds-connect-event :component c :event e
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

;;; Tests. Each case installs its own observer with
;;; `(with-redefs [!observer (atom …)] …)`, so none depends on (or leaks) the
;;; global one — RCF also runs in a live `bb dev` on ns load.

(tests
 "set-observer!: installs the fn, returns nil"
 (with-redefs [!observer (atom nil)]
   (set-observer! identity) := nil
   @!observer := identity)

 "set-observer!: nil removes the observer"
 (with-redefs [!observer (atom identity)]
   (set-observer! nil) := nil
   @!observer := nil))

(tests
 "notify!: the observer gets the observation"
 (with-redefs [!observer (atom (spy/spy))]
   (notify! {:type :sse-opened :component "a"})
   (spy/called-once-with? @!observer {:type :sse-opened :component "a"}) := true)

 "notify!: no observer → nothing happens, nil"
 (with-redefs [!observer (atom nil)]
   (notify! {:type :sse-opened :component "a"}) := nil)

 "notify!: a failing observer is swallowed (logged) — metrics never fail a request"
 (with-redefs [!observer (atom (fn [_] (throw (ex-info "observer down" {}))))]
   (notify! {:type :sse-opened :component "a"}) := nil))

(tests
 "machine-events*: :on keys at every depth, as names"
 (machine-events* {:id :t :initial :idle
                   :on {:GLOBAL :idle}
                   :states {:idle {:on {:INIT :idle :SEND {:target :busy}}}
                            :busy {:states {:inner {:on {:DONE :idle}}}}}})
 := #{"GLOBAL" "INIT" "SEND" "DONE"}

 "machine-events*: a machine with no :on anywhere → #{}"
 (machine-events* {:id :t :initial :idle :states {:idle {}}}) := #{})

(tests
 ;; discovery hands out the machine as a var, which discovered-machine derefs
 (def ^:private chat-machine {:id :chat :initial :idle :states {:idle {:on {:INIT :idle}}}})

 "discovered-machine: a component discovery never resolved → nil, without
  calling discovery (the cache is the gate)"
 (with-redefs [cache/cached                 (spy/spy (constantly nil))
               discovery/discover-component (spy/spy)]
   (discovered-machine :evil.thing "c1") := nil
   (spy/called-once-with? cache/cached ["evil.thing" "c1"]) := true
   (spy/not-called? discovery/discover-component) := true)

 "discovered-machine: a resolved component → its machine (the var's value)"
 (with-redefs [cache/cached                 (spy/spy (constantly {:controller-ns 'x :component-ns 'y}))
               discovery/discover-component (spy/spy (constantly {:machine #'chat-machine}))]
   (discovered-machine :chat.chat nil) := {:id :chat :initial :idle :states {:idle {:on {:INIT :idle}}}}
   (spy/called-once-with? cache/cached ["chat.chat" nil]) := true
   (spy/called-once-with? discovery/discover-component :chat.chat nil) := true)

 "discovered-machine: discovery throwing (the namespace went away) → nil"
 (with-redefs [cache/cached                 (constantly {:controller-ns 'x :component-ns 'y})
               discovery/discover-component (fn [& _] (throw (ex-info "gone" {})))]
   (discovered-machine :chat.chat nil) := nil))

(tests
 "labels: no discovered machine → unknown component, other event"
 (with-redefs [discovered-machine (spy/spy (constantly nil))]
   (labels :evil.thing "c1" "WHATEVER") := {:component "_unknown" :event "_other"}
   (spy/called-once-with? discovered-machine :evil.thing "c1") := true)

 "labels: a declared event (any case) keeps its upper-case name"
 (with-redefs [discovered-machine (spy/spy (constantly {:id :t :initial :idle
                                                        :states {:idle {:on {:INIT :idle}}}}))]
   (labels :chat.chat nil "init") := {:component "chat.chat" :event "INIT"}
   (spy/called-once-with? discovered-machine :chat.chat nil) := true)

 "labels: a declared event in a nested state keeps its name"
 (with-redefs [discovered-machine (constantly {:id :t :initial :idle
                                               :states {:idle {:on {:INIT :idle}}
                                                        :busy {:states {:inner {:on {:DONE :idle}}}}}})]
   (labels :chat.chat nil "DONE") := {:component "chat.chat" :event "DONE"})

 "labels: an undeclared event collapses to _other"
 (with-redefs [discovered-machine (constantly {:id :t :initial :idle
                                               :states {:idle {:on {:INIT :idle}}}})]
   (labels :chat.chat nil "RENDERER.foo") := {:component "chat.chat" :event "_other"}))

(tests
 "seconds-since: the seconds elapsed since a System/nanoTime reading"
 (-> (System/nanoTime)
     (- 1000000000)
     seconds-since
     (<= 60.0))
 := true
 (-> (System/nanoTime)
     (- 1000000000)
     seconds-since
     (>= 1.0))
 := true

 "error-response?: a Ring response with status >= 400"
 (error-response? {:status 400}) := true
 (error-response? {:status 500}) := true

 "error-response?: a 2xx/3xx, a map without :status, or not a map → false"
 (error-response? {:status 200}) := false
 (error-response? {:status 302}) := false
 (error-response? {:body "x"}) := false
 (error-response? ::anything) := false
 (error-response? nil) := false)

(tests
 "observe-event!: no observer → just calls f; nothing is labelled or reported"
 (with-redefs [!observer (atom nil)
               labels    (spy/spy)
               notify!   (spy/spy)]
   (observe-event! :nds :chat.chat nil "INIT" (constantly ::r)) := ::r
   (spy/not-called? labels) := true
   (spy/not-called? notify!) := true)

 "observe-event!: an ok result is returned, and reported as one :ok :event
  observation with its duration and bounded labels"
 (with-redefs [!observer     (atom identity)
               labels        (spy/spy (constantly {:component "chat.chat" :event "INIT"}))
               seconds-since (constantly 0.5)
               notify!       (spy/spy)]
   (observe-event! :nds :chat.chat nil "INIT" (constantly {:status 200})) := {:status 200}
   (spy/called-once-with? labels :chat.chat nil "INIT") := true
   (spy/called-once-with? notify! {:type :event :route :nds :outcome :ok :seconds 0.5
                                   :component "chat.chat" :event "INIT"})
   := true)

 "observe-event!: a 4xx/5xx response is returned, reported as an :error"
 (with-redefs [!observer     (atom identity)
               labels        (constantly {:component "_unknown" :event "_other"})
               seconds-since (constantly 0.5)
               notify!       (spy/spy)]
   (observe-event! :nds :nope nil "X" (constantly {:status 400})) := {:status 400}
   (spy/called-once-with? notify! {:type :event :route :nds :outcome :error :seconds 0.5
                                   :component "_unknown" :event "_other"})
   := true)

 "observe-event!: a throw is rethrown, reported as an :error"
 (with-redefs [!observer     (atom identity)
               labels        (constantly {:component "home.home" :event "INIT"})
               seconds-since (constantly 0.5)
               notify!       (spy/spy)]
   (observe-event! :nds-connect :home.home nil "INIT" #(throw (ex-info "boom" {})))
   :throws clojure.lang.ExceptionInfo
   (spy/called-once-with? notify! {:type :event :route :nds-connect :outcome :error :seconds 0.5
                                   :component "home.home" :event "INIT"})
   := true))

(tests
 "connection-opened!: no observer → nil token, nothing reported"
 (with-redefs [!observer (atom nil)
               notify!   (spy/spy)]
   (connection-opened! :home.home 42) := nil
   (spy/not-called? notify!) := true)

 "connection-opened!: reports :sse-opened under the component label and
  returns the token for close (the label, the start)"
 (with-redefs [!observer (atom identity)
               labels    (spy/spy (constantly {:component "home.home" :event "_other"}))
               notify!   (spy/spy)]
   (connection-opened! :home.home 42) := {:component "home.home" :started 42}
   (spy/called-once-with? labels :home.home nil nil) := true
   (spy/called-once-with? notify! {:type :sse-opened :component "home.home"}) := true)

 "connection-closed!: reports :sse-closed under the token's label, with the
  stream's lifetime"
 (with-redefs [seconds-since (spy/spy (constantly 12.5))
               notify!       (spy/spy)]
   (connection-closed! {:component "home.home" :started 42})
   (spy/called-once-with? seconds-since 42) := true
   (spy/called-once-with? notify! {:type :sse-closed :component "home.home" :seconds 12.5}) := true)

 "connection-closed!: a nil token (no observer at open) is a no-op"
 (with-redefs [notify! (spy/spy)]
   (connection-closed! nil) := nil
   (spy/not-called? notify!) := true))
