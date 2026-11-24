(ns taia.ds.datastar.adapters.handlers.default-component-handler
  "Dynamic endpoint handler for component events.
   Handles routes like /:component/:component-id/:event.

   The event runs through a component flow of its own
   (`process-component-event`: request → message → the process-event use
   case); the response
   shaping (`render-response`, `error-response`) lives in
   `taia.ds.datastar.adapters.handlers.response`. This namespace only owns the
   Ring handler entrypoint."
  (:require
   [taia.ds.datastar.adapters.handlers.observer :as observer]
   [taia.ds.datastar.adapters.handlers.discovery :as discovery]
   [taia.ds.datastar.adapters.handlers.request :as request]
   [taia.ds.datastar.use-cases.process-event :as process-event]
   [taia.ds.datastar.adapters.handlers.response :as response :refer [->html error-response render-response]]
   [hyperfiddle.rcf :refer [tests]]
   [spy.core :as spy]))

(defn process-component-event
  "One event of a one-shot component, from its request: resolves the component
   (discovery), turns the request into the flow's message and runs the
   process-event use case. Returns {:event :initial-ctx :ctx :html …} — `:html`
   is hiccup or an effects list. Throws what a step threw, or on timeout."
  [req component-name component-id event-name controller-name]
  (process-event/process-event
   (assoc (discovery/resolve-component component-name controller-name)
          :component-name component-name
          :component-id component-id)
   (request/->event-msg req component-id event-name)))

(tests
 "process-component-event: resolves the component, turns the request into the
  flow's message and hands both to the process-event use case, whose result
  it returns"
 (with-redefs [discovery/resolve-component (spy/spy (constantly {:machine-var ::machine :component-fn-var ::render}))
               request/->event-msg         (spy/spy (constantly {:event ["DO" {}]}))
               process-event/process-event (spy/spy (constantly {:html [:div "ok"]}))]
   (process-component-event {:body-params {} :params {:note "hi"}} :pce "1" "DO" "pce-ctrl")
   := {:html [:div "ok"]}
   (spy/called-once-with? discovery/resolve-component :pce "pce-ctrl") := true
   (spy/called-once-with? request/->event-msg {:body-params {} :params {:note "hi"}} "1" "DO") := true
   (spy/called-once-with? process-event/process-event
                          {:machine-var      ::machine
                           :component-fn-var ::render
                           :component-name   :pce
                           :component-id     "1"}
                          {:event ["DO" {}]})
   := true)

 "process-component-event: what a collaborator throws propagates"
 (with-redefs [discovery/resolve-component (spy/spy (fn [& _] (throw (ex-info "not found" {}))))
               process-event/process-event (spy/spy)]
   (process-component-event {:body-params {} :params {:note "hi"}} :nope "1" "DO" nil)
   :throws clojure.lang.ExceptionInfo
   (spy/not-called? process-event/process-event) := true))

;; End-to-end (only discovery stubbed): the request → render parity is the
;; point of these two — the pieces are tested in their own namespaces.
;; Discovery hands out vars, so the machine and render fns are vars here.
(tests
 (def ^:private pce-machine
   {:id :pce :initial :idle
    :states {:idle {:on {:DO {:target  :done
                              :actions (fn [ctx params]
                                         (assoc-in ctx [:memory :last-event] params))}}}
             :done {}}})
 (def ^:private pce-component-fn
   (fn [ctx]
     [:div {:state   (:state ctx)
            :grafana (get-in ctx [:app-config :grafana :base-url])
            :last    (get-in ctx [:memory :last-event])}]))
 (def ^:private pce-memory-fn (fn [ctx] [:div (:state ctx) (:memory ctx)]))

 "end-to-end: the render fn sees the post-transition ctx, the event params and
  :app-config"
 (with-redefs [discovery/discover-component
               (fn [_ _] {:machine #'pce-machine :component-fn #'pce-component-fn})]
   (-> {:body-params {}
        :params      {:logged-user {:user/id "u-1"} :note "hi"}
        :app-config  {:grafana {:base-url "http://localhost:3000"}}}
       (process-component-event :pce "1" "DO" nil)
       :html))
 := [:div {:state :done :grafana "http://localhost:3000" :last {:note "hi"}}]

 "end-to-end: the request's signal slice is the starting ctx"
 (with-redefs [discovery/discover-component
               (fn [_ _] {:machine #'pce-machine :component-fn #'pce-memory-fn})]
   (-> {:body-params {:1 {:state "done" :n 3}} :params {}}
       (process-component-event :pce "1" "RENDERER.LOADED" nil)
       :html))
 := [:div "done" {:n 3}])

(defn- default-component-handler
  "Default handler for component events.
   Discovers component, processes event through state machine,
   renders component, and returns an HTTP response. Timed as a :nds
   observation (`taia.ds.datastar.adapters.handlers.observer`); the 400 below is its
   :error outcome."
  [req component-name component-id event-name controller-name]
  (observer/observe-event!
   :nds component-name controller-name event-name
   (fn []
     (try
       (-> (process-component-event req component-name component-id event-name controller-name)
           (assoc :request req)
           render-response)
       (catch Exception e
         (error-response e component-name component-id event-name controller-name))))))

(tests
 (def ^:private boom (ex-info "boom" {:k :v}))
 (defn- observed-as
   "The route and labels default-component-handler observed the event under
    (all of observe-event!'s args but the thunk)."
   [observe-spy]
   (-> observe-spy
       spy/first-call
       butlast))
 (defn- run-thunk [_route _component _controller _event f] (f))

 "default-component-handler: the event's result, with the request attached,
  is rendered as the response — observed as an :nds event"
 (with-redefs [observer/observe-event!  (spy/spy run-thunk)
               process-component-event  (spy/spy (constantly {:html [:div "ok"]}))
               render-response          (spy/spy (constantly ::response))
               error-response           (spy/spy)]
   (default-component-handler {:r 1} :counter "c1" "INCREMENT" nil) := ::response
   (spy/called-once-with? process-component-event {:r 1} :counter "c1" "INCREMENT" nil) := true
   (spy/called-once-with? render-response {:html [:div "ok"] :request {:r 1}}) := true
   (spy/not-called? error-response) := true
   (observed-as observer/observe-event!) := [:nds :counter nil "INCREMENT"])

 "default-component-handler: the event throws → error-response for it, naming
  the component, instance, event and controller (its 400 is tested in
  `response`); nothing is rendered"
 (with-redefs [observer/observe-event!  (spy/spy run-thunk)
               process-component-event  (spy/spy (fn [& _] (throw boom)))
               render-response          (spy/spy)
               error-response           (spy/spy (constantly ::error-response))]
   (default-component-handler {} :counter "c1" "INCREMENT" "ctrl") := ::error-response
   (spy/called-once-with? error-response boom :counter "c1" "INCREMENT" "ctrl") := true
   (spy/not-called? render-response) := true
   (observed-as observer/observe-event!) := [:nds :counter "ctrl" "INCREMENT"]))

(defn default-handler
  "Main handler for dynamic component events.
   Supports two route patterns:
   - /:component/:component-id/:event (backward compatible)
   - /:component/:component-id/:controller/:event (with optional controller)
   
   Example: /ds/user-form/abc123/save
   - component: user-form
   - component-id: abc123
   - event: save
   
   Example with controller: /ds/counter/c1/counter-controller/increment
   - component: counter
   - component-id: c1
   - controller: counter-controller
   - event: increment
   
   Supports both synchronous (1-arity) and asynchronous (3-arity) handlers.
   Async version: [req respond raise]"
  ([req]
   (let [component-name (get-in req [:path-params :component])
         component-id (get-in req [:path-params :component-id])
         controller-name (get-in req [:path-params :controller])
         event-name (get-in req [:path-params :event])]
     (cond
       (not component-name)
       {:status 400 :body "Missing component name"}

       (not component-id)
       {:status 400 :body "Missing component-id"}

       (not event-name)
       {:status 400 :body "Missing event name"}

       :else
       (default-component-handler req
                                  (keyword component-name)
                                  component-id
                                  event-name
                                  controller-name))))
  ([req respond raise]
   (try
     (let [component-name (get-in req [:path-params :component])
           component-id (get-in req [:path-params :component-id])
           controller-name (get-in req [:path-params :controller])
           event-name (get-in req [:path-params :event])]
       (cond
         (not component-name)
         (respond {:status 400 :body "Missing component name"})

         (not component-id)
         (respond {:status 400 :body "Missing component-id"})

         (not event-name)
         (respond {:status 400 :body "Missing event name"})

         :else
         (try
           (let [response (default-component-handler req
                                                     (keyword component-name)
                                                     component-id
                                                     event-name
                                                     controller-name)]
             (respond response))
           (catch Exception e
             (raise e)))))
     (catch Exception e
       (raise e)))))

(tests
 (def ^:private broken (ex-info "broken" {}))

 "default-handler 1-arity: missing :component → 400"
 (default-handler {:path-params {:component-id "c1" :event "E"}})
 := {:status 400 :body "Missing component name"}

 "default-handler 1-arity: missing :component-id → 400"
 (default-handler {:path-params {:component "counter" :event "E"}})
 := {:status 400 :body "Missing component-id"}

 "default-handler 1-arity: missing :event → 400"
 (default-handler {:path-params {:component "counter" :component-id "c1"}})
 := {:status 400 :body "Missing event name"}

 "default-handler 1-arity: a missing path-param never reaches
  default-component-handler"
 (with-redefs [default-component-handler (spy/spy)]
   (default-handler {:path-params {}})
   (spy/not-called? default-component-handler) := true)

 "default-handler 1-arity: all path-params present → default-component-handler,
  with the component as a keyword and the controller path-param threaded
  through; its response is returned"
 (with-redefs [default-component-handler (spy/spy (constantly ::response))]
   (default-handler {:path-params {:component "counter" :component-id "c1" :controller "counter-ctrl" :event "INCREMENT"}}) := ::response
   (spy/called-once-with? default-component-handler
                          {:path-params {:component "counter" :component-id "c1" :controller "counter-ctrl" :event "INCREMENT"}}
                          :counter "c1" "INCREMENT" "counter-ctrl")
   := true)

 "default-handler 1-arity: no :controller path-param → a nil controller"
 (with-redefs [default-component-handler (spy/spy (constantly ::response))]
   (default-handler {:path-params {:component "counter" :component-id "c1" :event "INCREMENT"}}) := ::response
   (spy/called-once-with? default-component-handler
                          {:path-params {:component "counter" :component-id "c1" :event "INCREMENT"}}
                          :counter "c1" "INCREMENT" nil)
   := true)

 "default-handler 3-arity: missing path-param → respond called with 400"
 (with-redefs [default-component-handler (spy/spy)]
   (let [respond (spy/spy)
         raise   (spy/spy)]
     (default-handler {:path-params {}} respond raise)
     (spy/called-once-with? respond {:status 400 :body "Missing component name"}) := true
     (spy/not-called? raise) := true
     (spy/not-called? default-component-handler) := true))

 "default-handler 3-arity: all path-params present → respond called with
  default-component-handler's response"
 (with-redefs [default-component-handler (spy/spy (constantly ::response))]
   (let [respond (spy/spy)
         raise   (spy/spy)]
     (default-handler {:path-params {:component "counter" :component-id "c1" :controller "counter-ctrl" :event "INCREMENT"}} respond raise)
     (spy/called-once-with? respond ::response) := true
     (spy/not-called? raise) := true
     (spy/called-once-with? default-component-handler
                            {:path-params {:component "counter" :component-id "c1" :controller "counter-ctrl" :event "INCREMENT"}}
                            :counter "c1" "INCREMENT" "counter-ctrl")
     := true))

 "default-handler 3-arity: a component error is NOT raised — it is
  default-component-handler's 400 (tested above), which goes to respond like
  any response. Intentional: the 3-arity behaves like the 1-arity for
  component errors, so the client sees the diagnostic body."
 (with-redefs [default-component-handler (spy/spy (constantly {:status 400 :body "Error processing…"}))]
   (let [respond (spy/spy)
         raise   (spy/spy)]
     (default-handler {:path-params {:component "counter" :component-id "c1" :controller "counter-ctrl" :event "INCREMENT"}} respond raise)
     (spy/called-once-with? respond {:status 400 :body "Error processing…"}) := true
     (spy/not-called? raise) := true))

 "default-handler 3-arity: default-component-handler itself throws → raise
  is called with the exception, respond is not"
 (with-redefs [default-component-handler (spy/spy (fn [& _] (throw broken)))]
   (let [respond (spy/spy)
         raise   (spy/spy)]
     (default-handler {:path-params {:component "counter" :component-id "c1" :controller "counter-ctrl" :event "INCREMENT"}} respond raise)
     (spy/called-once-with? raise broken) := true
     (spy/not-called? respond) := true)))
