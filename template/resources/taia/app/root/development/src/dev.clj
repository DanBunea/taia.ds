(ns dev
  "Development entry point: the application plus an nREPL.

     bb dev                         ; or: clojure -M:dev -m dev
                                    ; the app on {{port}} + nREPL on {{nrepl-port}}

   From a REPL (yours, or the one `bb dev` opens on {{nrepl-port}}):

     (start!)    ; the app on {{port}}, returns its URL; idempotent
     (stop!)     ; stops it — open SSE streams close, so their flows stop too
     (restart!)  ; after changing routes or middleware (the router is built at start)

   These touch only the HTTP server, never the nREPL you are connected
   through. Component code needs no restart: handlers, machines and render fns
   are resolved through vars on every event — re-evaluate a namespace and the
   next click uses it.

   The HTTP wiring lives in `{{top/ns}}.{{main/ns}}.web.core` (the `web` base)."
  (:require
   [hyperfiddle.rcf]
   [nrepl.server :as nrepl]
   [taia.ds.datastar.interface :as ds]
   [{{top/ns}}.{{main/ns}}.web.core :as web]))

(comment
  (start!)
  (stop!)
  (restart!)
  (running?)

  nil) ; repl entrypoint

(def port {{port}})
(def nrepl-port {{nrepl-port}})

(defonce !url (atom nil))
(defonce !port (atom port))
(defonce !nrepl (atom nil))

(defn running?
  "The app's URL while it is up, else nil."
  []
  @!url)

(defn stop!
  "Stops the app. Idempotent; the nREPL keeps running."
  []
  (web/stop!)
  (when @!url (println "{{main}} stopped"))
  (reset! !url nil)
  nil)

(defn start!
  "Starts the app on `port` (default {{port}}) with RCF enabled, and returns
   its URL. Restarts it if it is already up."
  ([] (start! port))
  ([p]
   (hyperfiddle.rcf/enable!)
   (reset! !port p)
   (reset! !url (web/start! p))))

(defn restart!
  "stop! then start! on the port it last started on."
  []
  (stop!)
  (start! @!port))

(defn start-nrepl!
  "Starts the nREPL on `nrepl-port` (and writes .nrepl-port). Idempotent."
  []
  (when-not @!nrepl
    (reset! !nrepl (nrepl/start-server :port nrepl-port))
    (spit ".nrepl-port" nrepl-port)
    (println (str "nREPL on " nrepl-port)))
  nrepl-port)

(defn stop-nrepl!
  "Stops the nREPL — and with it a REPL connected through it."
  []
  (when-let [n @!nrepl]
    (nrepl/stop-server n)
    (reset! !nrepl nil)))

(defn -main [& _]
  (start!)
  (start-nrepl!)
  @(promise))

(comment
  ;; With the live counter page open (no `&user=` → you are "demo-user"):
  ;; who can be reached, as {component-id [user-id …]}
  (ds/connections)

  ;; a server EVENT into that tab's flow; its render is pushed down its stream.
  ;; A server event has no signals, so it brings the count as its params.
  (ds/send-event! "live1" "demo-user" "increment" {:count 2}) ; the tab shows 3

  ;; send-ui! pushes a hand-built fragment instead of an event; :broadcast
  ;; reaches every tab connected under the component-id.
  (ds/send-ui! "live1" "demo-user"
               [[:broadcast [:div#live1 [:h2 "Pushed from the REPL"]] {:d*/selector "#live1"}]]))
