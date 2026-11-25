(ns taia.ds.datastar.interface
  (:require
   [taia.ds.datastar.adapters.handlers.default-component-handler :as ce]
   [taia.ds.datastar.adapters.handlers.connected-component-handler :as cce]
   #?(:clj [taia.ds.datastar.adapters.handlers.request :as request])
   #?(:clj [taia.ds.datastar.use-cases.process-event :as process-event])
   #?(:clj [taia.ds.datastar.use-cases.process-event.common :as common])
   #?(:clj [taia.ds.datastar.adapters.handlers.discovery :as discovery])
   #?(:clj [taia.ds.datastar.adapters.handlers.observer :as observer])
   #?(:clj [taia.ds.datastar.adapters.handlers.response :as response])
   [taia.ds.datastar.adapters.handlers.context :as context]))

;; Dynamic component event handler
(def handler-component-event
  "Ring handler for dynamic component events (/ds/nds/<component>/<component-id>/<event>):
   discovers the component + controller, runs the FSM transition, renders the response."
  #'ce/default-handler)
;; Connected component handler (SSE)
(def handler-connected-component-connect
  "Async Ring handler for a connected component's path
   (/ds/nds-connect/<component>/<component-id>/<event>), mounted on ANY method.
   Every request opens a connection, whatever its event: an SSE stream and a
   new flow for the component, registered under (component-id, user) so
   `send-ui!` and `send-event!` reach that client. It replaces (and stops the
   flow of) any connection registered there before, so a reconnect or a reload
   takes over. The request's event is the first to run through the flow.
   Browser events after the open belong on `handler-component-event`
   (/ds/nds/…)."
  #'cce/handler-connected-component-connect)

#?(:clj
   (defn set-observer!
     "Installs `f` as the observer of component events on /ds/nds and
      /ds/nds-connect (nil removes it). `f` receives plain maps —
      {:type :event :route :component :event :outcome :seconds},
      {:type :sse-opened :component}, {:type :sse-closed :component :seconds} —
      with bounded labels and no component-id. See
      `taia.ds.datastar.adapters.handlers.observer`."
     [f]
     (observer/set-observer! f)))

#?(:clj
   (defn set-component-search-paths!
     "Sets the ordered list of namespace prefixes used to resolve components.
      Call once at startup with the value from app-config."
     [paths]
     (discovery/set-component-search-paths! paths)))

#?(:clj
   (defn warm-component-cache!
     "Pre-resolves the given Datastar components into the discovery cache so the
      first HTTP request for one does not pay the namespace-load cost on the
      request thread. Entries are component names (\"chat.chat\") or
      [component-name controller-name] pairs. Never throws; returns
      {:warmed n :failed [...] :elapsed-ms n}.
      Call AFTER `set-component-search-paths!` — setting the paths clears the cache."
     [component-names]
     (discovery/warm-cache! component-names)))

#?(:clj
   (defn process-component-event
     "Runs one event through a per-request component flow in-process
      (discovery → :controller FSM transition → :view render) and returns the
      result map with :html (hiccup or effects list) and :ctx. Throws what a
      step threw, or an ex-info after the event timeout.
      Lets a caller serve a component's full-page render at a canonical URL
      (e.g. GET /es → site.landing INIT) instead of redirecting into /ds/…"
     [req component-name component-id event-name controller-name]
     (ce/process-component-event req component-name component-id event-name controller-name)))

#?(:clj
   (defn send-ui!
     "Puts component-responses onto the UI channel for async delivery to
      the SSE client identified by component-id and user-id."
     [component-id user-id component-responses]
     (cce/send-ui! component-id user-id component-responses)))

#?(:clj
   (defn start-component-flow!
     "Starts a component's flow headless — no SSE stream: the machine and
      render fn are resolved now, like a connected component's flow. Drive it
      with `run-flow-event!`; stop it with `stop-component-flow!`. Useful to
      test a connected component in process.
      opts: {:component-name :component-id :controller-name}"
     [opts]
     (let [{:keys [component-name controller-name]} opts]
       (process-event/start-flow!
        (merge opts (discovery/resolve-component component-name controller-name))))))

#?(:clj
   (defn run-flow-event!
     "Runs one event through a flow from `start-component-flow!` and returns
      its result map (:ctx, :html, …). `req` carries the client signals and
      params like a request would; nil makes it a server event, with
      `params` (what `send-event!` sends) as its params."
     ([component-flow req event-name]
      (run-flow-event! component-flow req event-name {}))
     ([component-flow req event-name params]
      (process-event/run-event!
       component-flow
       (if req
         (request/->event-msg req (:component-id component-flow) event-name)
         (common/server-event event-name params))))))

#?(:clj
   (defn stop-component-flow!
     "Stops a flow from `start-component-flow!`."
     [component-flow]
     (process-event/stop-flow! component-flow)))

#?(:clj
   (defn connections
     "The open SSE streams, as {component-id [user-id …]}: who `send-ui!` and
      `send-event!` can reach right now. When `send-event!` returns false,
      look here — the stream is registered under the `:user/id` of the
      request that opened it."
     []
     (cce/connections)))

#?(:clj
   (defn send-event!
     "Sends a server event to the connected component (component-id, user-id):
      it runs through that connection's component flow — from the machine's
      initial state, with `params` — and the render is pushed down the open
      SSE stream. `event-name` is case-insensitive like a URL event; `params` is
      the event's params map. Returns true when queued, false when that user
      has no open connection under component-id."
     [component-id user-id event-name params]
     (cce/send-event! component-id user-id event-name params)))

(defn ctx->data-signals
  "The `data-signals` attribute value for a component's root element: the
   component's `:memory` plus `:state`, namespaced under its component-id, as
   JSON. Datastar sends this store back with every @post/@get, which is how
   `:memory` round-trips between requests. Pure; never throws."
  [ctx]
  (context/ctx->data-signals ctx))

#?(:clj
   (defn html-response
     "Ring 200 text/html response for `hiccup` — e.g. the full-page render that
      `process-component-event` returns for a GET at a canonical URL."
     [hiccup]
     (response/->html hiccup)))
