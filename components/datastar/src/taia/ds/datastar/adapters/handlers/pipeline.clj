(ns taia.ds.datastar.adapters.handlers.pipeline
  "Shared component event processing pipeline.
   Extracts the core steps (discovery, event extraction, state machine,
   rendering) used by both the default HTTP handler and the SSE connect handler."
  (:require
   [charred.api :as charred]
   [taia.ds.datastar.adapters.handlers.discovery :as discovery]
   [clojure.string :as str]
   [clojure.main :as main]
   [taia.ds.extensions.interface :as ext]
   [hyperfiddle.rcf :refer [tests]]
   [taia.ds.logging.interface :as log]
   [spy.core :as spy]
   [taia.ds.state-machines.interface :as sm :refer [transition]]))

(defn- fn-name
  "Extracts a human-readable function name from a function object.
   e.g. my.app.components.chat.chat_actions$root_init
     -> chat-actions/root-init"
  [f]
  (let [full (main/demunge (-> f class .getName))
        ;; full looks like: some.ns/fn-name (after demunge)
        ;; take last ns segment + fn name for brevity
        parts (str/split full #"/")]
    (if (= 2 (count parts))
      (let [ns-part (last (str/split (first parts) #"\."))
            fn-part (second parts)]
        (str ns-part "/" fn-part))
      full)))

(tests
 "fn-name: a fn defined in a multi-segment ns → last-segment/fn-name"
 (fn-name clojure.string/blank?) := "string/blank?"

 "fn-name: a fn defined in clojure.core (multi-segment) returns core/<fn>"
 (fn-name clojure.core/identity) := "core/identity"

 "fn-name: anonymous fn falls back to a non-empty string (no clean ns/fn split)"
 (let [out (fn-name (fn [_]))]
   (and (string? out) (pos? (count out)))) := true)

(defn assoc-component-fn-and-machine
  "Associates :machine-var and :component-fn-var by discovering the component.
   Keeps vars instead of dereferencing immediately so changes in REPL are reflected.
   Optionally uses controller-name if provided, otherwise uses default controller naming."
  [{:keys [component-name controller-name] :as m}]
  (let [discovered (discovery/discover-component component-name controller-name)]
    (-> m
        (assoc :machine-var (:machine discovered))
        (assoc :component-fn-var (:component-fn discovered)))))

(tests
 "assoc-component-fn-and-machine: keeps the machine + component-fn as vars
  (not dereffed) so REPL redefs are picked up"
 (def ^:private machine-fixture {:id :test-component
                                 :initial :initial-state
                                 :states {:initial-state {}}})
 (def ^:private component-fn-fixture (fn [_ctx] [:div "test component"]))

 (with-redefs [discovery/discover-component
               (spy/spy (fn
                          ([_]   {:machine #'machine-fixture
                                  :component-fn #'component-fn-fixture})
                          ([_ _] {:machine #'machine-fixture
                                  :component-fn #'component-fn-fixture})))]
   (let [result (assoc-component-fn-and-machine {:component-name "component-name"})]
     [(var? (:machine-var result))
      (var? (:component-fn-var result))
      @(:machine-var result)
      @(:component-fn-var result)])) := [true true machine-fixture component-fn-fixture])

(defn assoc-event
  "Extracts event parameters from the request. The raw `:datastar` query param
   (the GET-request signal store — consumed by assoc-initial-ctx-and-state) is
   dropped like the other plumbing keys."
  [{:keys [request component-id] :as m} event-name]
  (assoc m :event [event-name (-> request
                                  :body-params
                                  (dissoc (keyword component-id))
                                  (merge (get-in request [:params]))
                                  (dissoc :logged-user :component-name :datastar))]))

(tests
 "assoc-event: body-params (with component-id key dropped) merged with params
  (sans :logged-user / :component-name) become event-params"
 (assoc-event {:request {:body-params {:c1 {:count 5} :other-data "value"}
                         :params     {:logged-user {:user/id "123"}
                                      :component-name :counter
                                      :other-param "param-value"}}
               :component-id "c1"
               :other-key "preserved"}
              "increment")
 := {:request {:body-params {:c1 {:count 5} :other-data "value"}
               :params     {:logged-user {:user/id "123"}
                            :component-name :counter
                            :other-param "param-value"}}
     :component-id "c1"
     :other-key "preserved"
     :event ["increment" {:other-data "value"
                          :other-param "param-value"}]}

 "assoc-event: empty body-params, non-empty params"
 (:event (assoc-event {:request {:body-params {}
                                 :params     {:logged-user {:user/id "123"}
                                              :component-name :test
                                              :param1 "value1"
                                              :param2 "value2"}}
                       :component-id "xyz"}
                      "save"))
 := ["save" {:param1 "value1" :param2 "value2"}]

 "assoc-event: body-params with component-id key, empty params"
 (:event (assoc-event {:request {:body-params {:c2 {:state :active} :data "test"}
                                 :params {}}
                       :component-id "c2"}
                      "update"))
 := ["update" {:data "test"}]

 "assoc-event: the raw :datastar query param (GET signal store) never leaks
  into event-params; explicit query params still ride"
 (:event (assoc-event {:request {:body-params {}
                                 :params {:datastar "{\"c3\":{\"state\":\"x\"}}"
                                          :session-id "s1"}}
                       :component-id "c3"}
                      "load"))
 := ["load" {:session-id "s1"}])

(defn assoc-initial-ctx-and-state
  "Extracts context from the request.
   Includes :component-route so actions (e.g. completion-endpoint) can build
   URLs for the correct component without hardcoding.
   Forwards `:app-config` from the request (attached by ds-routes' wrap-app-config
   middleware) so SM actions can read config values without a global atom — but
   only when actually present, so ctx in unrelated tests stays minimal."
  [{:keys [request component-id component-name machine-var] :as m}]
  (let [machine @machine-var  ;; Dereference var to get latest value
        logged-user (get-in request [:params :logged-user])
        app-config  (get request :app-config)
        cid (keyword component-id)
        ;; POST events carry the signal store as the JSON body (muuntaja →
        ;; :body-params); GET events — e.g. an @get to /ds/nds-connect/… —
        ;; carry the SAME store as the `datastar` query param. Parse it so a
        ;; connected GET hydrates :memory/:state exactly like a POST (the
        ;; :state string is coerced downstream identically for both).
        body-memory (or (get-in request [:body-params cid])
                        (some-> (get-in request [:params :datastar])
                                (charred/read-json :key-fn keyword)
                                (get cid)))

        memory (-> (or body-memory {})
                   (dissoc :state))
        state (or (-> body-memory
                      :state)
                  (:initial machine))]
    (assoc m :initial-ctx
           (-> {:state state
                :memory memory
                :component-id component-id
                :component-route component-name
                :logged-user logged-user}
               (ext/assoc-if :app-config app-config app-config)
               ;; the HTTP method, for a transition that must not happen on a
               ;; GET (a link can trigger a GET; a cross-site POST carries no
               ;; Lax session cookie)
               (ext/assoc-if :request-method (:request-method request) (:request-method request))))))

(tests
 ;; Test 1: body-memory with state, logged-user present
 (def request-1 {:body-params {:c1 {:count 5 :state :active}}
                 :params {:logged-user {:user/id "123" :user/name "Test"}}})
 (def machine-1 {:id :test :initial :initial-state :states {}})
 (def machine-1-var (var machine-1))

 (assoc-initial-ctx-and-state {:request request-1
                               :component-id "c1"
                               :component-name :test-comp
                               :machine-var machine-1-var
                               :other-key "preserved"}) := {:request request-1
                                                            :component-id "c1"
                                                            :component-name :test-comp
                                                            :machine-var machine-1-var
                                                            :other-key "preserved"
                                                            :initial-ctx {:state :active
                                                                          :memory {:count 5}
                                                                          :component-id "c1"
                                                                          :component-route :test-comp
                                                                          :logged-user {:user/id "123" :user/name "Test"}}}

 "the request method rides along when the request has one"
 (:request-method (:initial-ctx (assoc-initial-ctx-and-state
                                 {:request (assoc request-1 :request-method :post)
                                  :component-id "c1" :component-name :test-comp
                                  :machine-var machine-1-var})))
 := :post

 ;; Test 2: body-memory without state (uses machine initial), logged-user present
 (def request-2 {:body-params {:c2 {:count 10}}
                 :params {:logged-user {:user/id "456"}}})
 (def machine-2 {:id :test :initial :default-state :states {}})
 (def machine-2-var (var machine-2))

 (assoc-initial-ctx-and-state {:request request-2
                               :component-id "c2"
                               :component-name :comp-2
                               :machine-var machine-2-var}) := {:request request-2
                                                                :component-id "c2"
                                                                :component-name :comp-2
                                                                :machine-var machine-2-var
                                                                :initial-ctx {:state :default-state
                                                                              :memory {:count 10}
                                                                              :component-id "c2"
                                                                              :component-route :comp-2
                                                                              :logged-user {:user/id "456"}}}

 ;; Test 3: no body-memory (nil), no logged-user → memory defaults to {}
 (def request-3 {:body-params {}
                 :params {}})
 (def machine-3 {:id :test :initial :start :states {}})
 (def machine-3-var (var machine-3))

 (assoc-initial-ctx-and-state {:request request-3
                               :component-id "c3"
                               :component-name nil
                               :machine-var machine-3-var}) := {:request request-3
                                                                :component-id "c3"
                                                                :component-name nil
                                                                :machine-var machine-3-var
                                                                :initial-ctx {:state :start
                                                                              :memory {}
                                                                              :component-id "c3"
                                                                              :component-route nil
                                                                              :logged-user nil}}

 ;; Test 4: body-memory with state and other keys
 (def request-4 {:body-params {:c4 {:state :loading :data "test" :count 0}}
                 :params {:logged-user {:user/id "789"}}})
 (def machine-4 {:id :test :initial :idle :states {}})
 (def machine-4-var (var machine-4))

 (assoc-initial-ctx-and-state {:request request-4
                               :component-id "c4"
                               :component-name :comp-4
                               :machine-var machine-4-var}) := {:request request-4
                                                                :component-id "c4"
                                                                :component-name :comp-4
                                                                :machine-var machine-4-var
                                                                :initial-ctx {:state :loading
                                                                              :memory {:data "test" :count 0}
                                                                              :component-id "c4"
                                                                              :component-route :comp-4
                                                                              :logged-user {:user/id "789"}}}

 ;; Test 5: when the request carries :app-config (attached upstream by
 ;; ds-routes' wrap-app-config), it flows through to the initial-ctx so SM
 ;; actions can read config without a global. Absence is preserved as
 ;; absence (tests 1-4 above) — only present values get carried over.
 (def request-5 {:body-params {:c5 {}}
                 :params {:logged-user {:user/id "999"}}
                 :app-config {:grafana {:base-url "http://localhost:3000"} :env :dev}})
 (def machine-5 {:id :test :initial :ready :states {}})
 (def machine-5-var (var machine-5))

 (-> (assoc-initial-ctx-and-state {:request request-5
                                   :component-id "c5"
                                   :component-name :comp-5
                                   :machine-var machine-5-var})
     :initial-ctx
     :app-config) := {:grafana {:base-url "http://localhost:3000"} :env :dev}

 ;; Test 6: a GET request (e.g. @get to /ds/nds-connect/…) has no JSON body —
 ;; the signal store rides the `datastar` query param instead. It must hydrate
 ;; :memory/:state exactly like a POST body (the state string is coerced
 ;; downstream identically for both paths).
 (def request-6 {:body-params {}
                 :params {:logged-user {:user/id "123"}
                          :datastar "{\"c6\":{\"count\":7,\"state\":\"displaying\"}}"}})
 (def machine-6 {:id :test :initial :not-initialised :states {}})
 (def machine-6-var (var machine-6))

 (-> (assoc-initial-ctx-and-state {:request request-6
                                   :component-id "c6"
                                   :component-name :comp-6
                                   :machine-var machine-6-var})
     :initial-ctx
     (select-keys [:state :memory]))
 := {:state "displaying" :memory {:count 7}}

 ;; Test 7: a present JSON body wins over the query param (POST behavior
 ;; unchanged); absent both → machine initial (test 3 above)
 (-> (assoc-initial-ctx-and-state
      {:request {:body-params {:c7 {:state :active :count 1}}
                 :params {:datastar "{\"c7\":{\"count\":99,\"state\":\"stale\"}}"}}
       :component-id "c7"
       :component-name :comp-7
       :machine-var machine-6-var})
     :initial-ctx
     (select-keys [:state :memory]))
 := {:state :active :memory {:count 1}})

(defn- coerce-state-to-keywords
  "Coerce :state to keywords; support hierarchical state as vector (e.g. [:home :home-displayed])."
  [state]
  (cond (vector? state) (mapv keyword state)
        (some? state)   (keyword state)
        :else           state))

(tests
 "coerce-state-to-keywords: vector → mapv keyword (hierarchical state)"
 (coerce-state-to-keywords ["home" "home-displayed"]) := [:home :home-displayed]

 "coerce-state-to-keywords: vector containing keywords stays a vector of keywords"
 (coerce-state-to-keywords [:home :home-displayed]) := [:home :home-displayed]

 "coerce-state-to-keywords: string → keyword"
 (coerce-state-to-keywords "active") := :active

 "coerce-state-to-keywords: keyword passes through (no-op)"
 (coerce-state-to-keywords :active) := :active

 "coerce-state-to-keywords: nil passes through"
 (coerce-state-to-keywords nil) := nil)

(defn assoc-process-event-results
  "Processes the event through the state machine and associates results.
   Skips DataStar internal events (RENDERER.* and INSTALLHOOK.*) which are
   automatically sent by the client-side JavaScript library.
   Injects :trace-fn and :action-trace-fn into HSM opts when log-level >= :debug."
  [{:keys [initial-ctx event machine-var] :as m}]
  (let [machine @machine-var  ;; Dereference var to get latest value
        [event-name event-params] event
        event-keyword (keyword (str/upper-case event-name))
        event-name-str (name event-keyword)
        ;; Skip DataStar internal events
        skip-event? (or (str/starts-with? event-name-str "RENDERER.")
                        (str/starts-with? event-name-str "INSTALLHOOK."))]
    (assoc m :ctx
           (if skip-event?
             ;; For internal events, just return the context unchanged (no state transition)
             initial-ctx
             (let [ctx-with-coerced-state (update initial-ctx :state coerce-state-to-keywords)
                   min-level (log/log-level)
                   trace-debug? (log/trace-enabled? :debug min-level)
                   trace-fn (when trace-debug?
                              (fn [invoke-id src-fn context]
                                (log/trace (keyword (str "invoke/" (fn-name src-fn)))
                                           []
                                           (src-fn context))))
                   action-trace-fn (when trace-debug?
                                     (fn [action-id action-fn & args]
                                       (log/trace (keyword (str "action/" (fn-name action-fn)))
                                                  []
                                                  (apply action-fn args))))
                   transition-opts (-> {:state-key :state}
                                       (ext/assoc-if :trace-fn trace-fn trace-fn)
                                       (ext/assoc-if :action-trace-fn action-trace-fn action-trace-fn))
                   result (log/trace-when (log/trace-enabled? :verbose min-level)
                                          (keyword (str "transition-from" (:state ctx-with-coerced-state) "-" event-keyword))
                                          []
                                          (transition machine ctx-with-coerced-state [event-keyword event-params]
                                                      transition-opts))]
               (assoc result :from-state (:state ctx-with-coerced-state)))))))

(tests
 "assoc-process-event-results: state coerced, event uppercased, transition called,
  :from-state stamped from coerced state"
 (def ^:private aper-machine-1 {:id :test :initial :idle :states {:idle {}}})
 (with-redefs [transition (spy/spy (fn [_machine _from-state _evt _opts]
                                     {:state :active
                                      :memory {:count 6}
                                      :component-id "c1"}))]
   (let [result (assoc-process-event-results
                 {:initial-ctx {:state "active"
                                :memory {:count 5}
                                :component-id "c1"
                                :logged-user {:user/id "123"}}
                  :event ["increment" {:amount 1}]
                  :machine-var #'aper-machine-1
                  :other-key "preserved"})]
     [(:other-key result)
      (:ctx result)
      (spy/calls transition)]))
 := ["preserved"
     {:state :active :memory {:count 6} :component-id "c1" :from-state :active}
     [[aper-machine-1
       {:memory {:count 5} :component-id "c1" :logged-user {:user/id "123"} :state :active}
       [:INCREMENT {:amount 1}]
       {:state-key :state}]]]

 "assoc-process-event-results: lowercase event-name uppercased into the event-keyword"
 (def ^:private aper-machine-2 {:id :test :initial :idle :states {}})
 (with-redefs [transition (spy/spy (fn [_ _ _ _] {:state :saved :memory {:data "test"}}))]
   (assoc-process-event-results
    {:initial-ctx {:state "idle" :memory {} :component-id "c2"}
     :event ["save" {:data "test"}]
     :machine-var #'aper-machine-2})
   (-> (spy/calls transition) first (nth 2)))
 := [:SAVE {:data "test"}]

 "assoc-process-event-results: skips RENDERER.* internal events — initial-ctx
  passes through to :ctx without invoking transition"
 (with-redefs [transition (spy/spy (fn [_ _ _ _] (throw (ex-info "should not be called" {}))))]
   (let [result (assoc-process-event-results
                 {:initial-ctx {:state :idle :memory {}}
                  :event ["RENDERER.LOADED" {}]
                  :machine-var #'aper-machine-2})]
     [(:ctx result) (spy/not-called? transition)]))
 := [{:state :idle :memory {}} true]

 "assoc-process-event-results: skips INSTALLHOOK.* internal events too"
 (with-redefs [transition (spy/spy (fn [_ _ _ _] (throw (ex-info "should not be called" {}))))]
   (let [result (assoc-process-event-results
                 {:initial-ctx {:state :idle}
                  :event ["INSTALLHOOK.READY" {}]
                  :machine-var #'aper-machine-2})]
     [(:ctx result) (spy/not-called? transition)]))
 := [{:state :idle} true])

(defn add-removal-nils
  "Returns new-memory augmented with nil entries for every key (recursively)
   present in old-memory but absent in new. nil serializes to JSON null, which
   Datastar's RFC 7386 merge-patch deletes from the client signal store — so a
   plain dissoc on :memory becomes a real client-side signal removal.
   Vectors are atomic (replaced wholesale, never recursed into), matching
   merge-patch array semantics. Framework contract: renderers must treat nil
   and absent memory keys identically."
  [old new]
  (reduce-kv
   (fn [acc k old-v]
     (if (contains? acc k)
       (let [new-v (get acc k)]
         (if (and (map? old-v) (map? new-v))
           (assoc acc k (add-removal-nils old-v new-v))
           acc))
       (assoc acc k nil)))
   (or new {})
   (or old {})))

(tests
 "add-removal-nils: no removals → new returned unchanged"
 (add-removal-nils {:a 1} {:a 2}) := {:a 2}

 "add-removal-nils: top-level removed key → nil"
 (add-removal-nils {:a 1 :b 2} {:a 1}) := {:a 1 :b nil}

 "add-removal-nils: key removed inside a surviving nested map → nested nil"
 (add-removal-nils {:selected {:a 1 :b 2}} {:selected {:a 1}}) := {:selected {:a 1 :b nil}}

 "add-removal-nils: whole nested map removed → top-level nil"
 (add-removal-nils {:selected {:a 1}} {}) := {:selected nil}

 "add-removal-nils: map replaced by scalar kept verbatim (merge-patch replaces subtree)"
 (add-removal-nils {:selected {:a 1}} {:selected "x"}) := {:selected "x"}

 "add-removal-nils: vectors are atomic — never recursed into"
 (add-removal-nils {:xs [1 2 3]} {:xs [1]}) := {:xs [1]}

 "add-removal-nils: key only in new untouched; explicit nil in new preserved
  (backward compat with existing assoc-to-nil sites)"
 (add-removal-nils {:a 1} {:a nil :fresh 2}) := {:a nil :fresh 2}

 "add-removal-nils: nil/empty old → new unchanged"
 (add-removal-nils nil {:a 1}) := {:a 1}
 (add-removal-nils {} {:a 1}) := {:a 1}

 "add-removal-nils: nil new with non-empty old → all keys nil'd"
 (add-removal-nils {:a 1 :b 2} nil) := {:a nil :b nil}

 "add-removal-nils: wholesale :memory rebuild — every old client key not
  re-included is deleted client-side"
 (add-removal-nils {:contacts {:u1 {:selected true}} :q "x"} {:fresh 1})
 := {:fresh 1 :contacts nil :q nil})

(defn assoc-removed-signal-nils
  "Pipeline step between assoc-process-event-results and assoc-html.
   Re-assocs (as nil) every memory key the client sent that the transition
   removed, so serialization (ctx->data-signals / patch-signals built from
   :memory) emits JSON nulls and the client deletes those signals. No-op when
   nothing was removed — including the RENDERER.*/INSTALLHOOK.* skip path
   where :ctx is :initial-ctx."
  [{:keys [initial-ctx ctx] :as m}]
  (assoc-in m [:ctx :memory]
            (add-removal-nils (:memory initial-ctx) (:memory ctx))))

(tests
 "assoc-removed-signal-nils: removed key re-added as nil in [:ctx :memory];
  all other pipeline + ctx keys preserved"
 (assoc-removed-signal-nils
  {:initial-ctx {:state :idle :memory {:stale "x" :keep 1} :component-id "c1"}
   :ctx {:state :done :memory {:keep 1} :component-id "c1" :from-state :idle}
   :other-key "preserved"})
 := {:initial-ctx {:state :idle :memory {:stale "x" :keep 1} :component-id "c1"}
     :ctx {:state :done :memory {:keep 1 :stale nil} :component-id "c1" :from-state :idle}
     :other-key "preserved"}

 "assoc-removed-signal-nils: ctx == initial-ctx (internal-event skip path) → identity"
 (def ^:private arsn-skip-ctx {:state :idle :memory {:a 1} :component-id "c1"})
 (assoc-removed-signal-nils {:initial-ctx arsn-skip-ctx :ctx arsn-skip-ctx})
 := {:initial-ctx arsn-skip-ctx :ctx arsn-skip-ctx}

 "assoc-removed-signal-nils: empty initial memory (SSE-connect shape) → no-op"
 (-> (assoc-removed-signal-nils
      {:initial-ctx {:state :start :memory {}}
       :ctx {:state :ready :memory {:loaded true}}})
     (get-in [:ctx :memory]))
 := {:loaded true})

(defn assoc-html
  "Renders the component and associates the HTML."
  [{:keys [component-fn-var ctx] :as m}]
  (let [component-fn @component-fn-var]  ;; Dereference var to get latest value
    (assoc m :html
           (log/trace-when (log/trace-enabled? :verbose (log/log-level))
                           ::render-component []
                           (component-fn ctx)))))

(tests
 "assoc-html: component-fn-var is dereffed and called with ctx; result stored at :html"
 (def ^:private html-fn-fixture (fn [ctx] [:div "ctx-state=" (str (:state ctx))]))
 (assoc-html {:component-fn-var #'html-fn-fixture
              :ctx              {:state :displayed}
              :other-key        "preserved"})
 := {:component-fn-var #'html-fn-fixture
     :ctx              {:state :displayed}
     :other-key        "preserved"
     :html             [:div "ctx-state=" ":displayed"]}

 "assoc-html: component fn returning an effects list is stored verbatim
  (no rendering happens here — that's render-response's job)"
 (def ^:private html-fn-effect (fn [_] [[:execute-script "window.location.href='/x'"]]))
 (-> (assoc-html {:component-fn-var #'html-fn-effect :ctx {}})
     :html)
 := [[:execute-script "window.location.href='/x'"]])

(defn process-component-event
  "Runs the component pipeline: discovery, event extraction, state machine,
   and rendering. Returns the result map with :html key containing the
   rendered component (plain hiccup or effects list)."
  [req component-name component-id event-name controller-name]
  (log/trace-when (log/trace-enabled? :verbose (log/log-level))
                  (keyword (str "process-component-event/" event-name))
                  [:component-name component-name
                   :component-id component-id
                   :event-name event-name
                   :controller-name controller-name]
                  (-> {:request req
                       :component-name component-name
                       :component-id component-id
                       :controller-name controller-name}
                      assoc-component-fn-and-machine
                      (assoc-event event-name)
                      assoc-initial-ctx-and-state
                      assoc-process-event-results
                      assoc-removed-signal-nils
                      assoc-html)))

(tests
 "process-component-event: full happy-path pipeline — discovery → event →
  initial-ctx (with :app-config forwarded) → SM transition → render. The
  component fn sees the post-transition ctx and returns hiccup that closes
  over both event-params and :app-config."
 (def ^:private pce-machine
   {:id :pce :initial :idle
    :states {:idle {:on {:DO {:target  :done
                              :actions (fn [ctx params]
                                         (assoc-in ctx [:memory :last-event] params))}}}
             :done {}}})

 (def ^:private pce-component-fn
   (fn [ctx]
     [:div
      {:state    (:state ctx)
       :grafana  (get-in ctx [:app-config :grafana :base-url])
       :last     (get-in ctx [:memory :last-event])}]))

 (with-redefs [discovery/discover-component
               (fn ([_]   {:machine #'pce-machine :component-fn #'pce-component-fn})
                 ([_ _] {:machine #'pce-machine :component-fn #'pce-component-fn}))]
   (let [req    {:body-params {}
                 :params      {:logged-user {:user/id "u-1"}
                               :note        "hi"}
                 :app-config  {:grafana {:base-url "http://localhost:3000"}}}
         result (process-component-event req :pce "1" "DO" nil)]
     (:html result)))
 := [:div {:state :done
           :grafana "http://localhost:3000"
           :last {:note "hi"}}]

 "process-component-event: a memory key dissoc'd by an action is re-emitted as
  nil in the rendered ctx, so the data-signals round-trip deletes it client-side"
 (def ^:private pce-dissoc-machine
   {:id :pce-dissoc :initial :idle
    :states {:idle {:on {:CLEAR {:target  :done
                                 :actions (fn [ctx _params]
                                            (update ctx :memory dissoc :stale))}}}
             :done {}}})

 (def ^:private pce-memory-component-fn
   (fn [ctx] [:div {:memory (:memory ctx)}]))

 (with-redefs [discovery/discover-component
               (fn ([_]   {:machine #'pce-dissoc-machine :component-fn #'pce-memory-component-fn})
                 ([_ _] {:machine #'pce-dissoc-machine :component-fn #'pce-memory-component-fn}))]
   (-> (process-component-event {:body-params {:1 {:stale "x" :keep 1 :state "idle"}}
                                 :params      {}}
                                :pce-dissoc "1" "CLEAR" nil)
       :html
       (get-in [1 :memory])))
 := {:keep 1 :stale nil}

 "process-component-event: RENDERER.* internal event short-circuits transition,
  component fn still rendered against initial-ctx"
 (with-redefs [discovery/discover-component
               (fn ([_]   {:machine #'pce-machine :component-fn #'pce-component-fn})
                 ([_ _] {:machine #'pce-machine :component-fn #'pce-component-fn}))]
   (-> (process-component-event {:body-params {} :params {}} :pce "1" "RENDERER.LOADED" nil)
       :html
       (get-in [1 :state])))
 := :idle)
