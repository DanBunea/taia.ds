(ns taia.ds.datastar.interface
  (:require
   [taia.ds.datastar.adapters.handlers.default-component-handler :as ce]
   [taia.ds.datastar.adapters.handlers.connected-component-handler :as cce]
   #?(:clj [taia.ds.datastar.adapters.handlers.discovery :as discovery])
   #?(:clj [taia.ds.datastar.adapters.handlers.observer :as observer])
   #?(:clj [taia.ds.datastar.adapters.handlers.pipeline :as pipeline])))

;; Dynamic component event handler
(def handler-component-event
  "Ring handler for dynamic component events (/ds/nds/<component>/<component-id>/<event>):
   discovers the component + controller, runs the FSM transition, renders the response."
  #'ce/default-handler)
;; Connected component handler (SSE)
(def handler-connected-component-connect
  "Async Ring handler that opens an SSE connection for a connected component
   and registers it so `send-ui!` can push updates to that client."
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
     "Runs the component pipeline in-process (discovery → FSM transition →
      render) and returns the result map with :html (hiccup or effects list).
      Lets a caller serve a component's full-page render at a canonical URL
      (e.g. GET /es → site.landing INIT) instead of redirecting into /ds/…"
     [req component-name component-id event-name controller-name]
     (pipeline/process-component-event req component-name component-id event-name controller-name)))

#?(:clj
   (defn send-ui!
     "Puts component-responses onto the UI channel for async delivery to
      the SSE client identified by component-id and user-id."
     [component-id user-id component-responses]
     (cce/send-ui! component-id user-id component-responses)))
