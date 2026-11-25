(ns dev
  "Development entry point: the examples application plus an nREPL.

     bb dev                         ; or: clojure -M:dev -m dev
                                    ; examples app on 7788 + nREPL on 7789

   From a REPL (yours, or the one `bb dev` opens on 7789):

     (start!)    ; examples app on 7788, returns the examples URL; idempotent
     (stop!)     ; stops it — open SSE streams close, so their flows stop too
     (restart!)  ; after changing routes or middleware (the router is built at start)

   These touch only the HTTP server, never the nREPL you are connected
   through. Component code needs no restart: handlers, machines and render fns
   are resolved through vars on every event.

   The HTTP wiring lives in `taia.ds.examples.core` (the `examples` base), which
   is also the reference for how an application base mounts the library."
  (:require
   [hyperfiddle.rcf]
   [nrepl.server :as nrepl]
   [taia.ds.datastar.interface :as ds]
   [taia.ds.examples.core :as examples]))

(comment
  (start!)
  (stop!)
  (restart!)
  (running?)

  nil) ; repl entrypoint

(def port 7788)
(def nrepl-port 7789)

(defonce !url (atom nil))
(defonce !port (atom port))
(defonce !nrepl (atom nil))

(defn running?
  "The examples URL while the app is up, else nil."
  []
  @!url)

(defn stop!
  "Stops the examples app. Idempotent; the nREPL keeps running."
  []
  (examples/stop!)
  (when @!url (println "taia.ds examples stopped"))
  (reset! !url nil)
  nil)

(defn start!
  "Starts the examples app on `port` (default 7788) with RCF enabled, and
   returns the examples URL. Restarts it if it is already up."
  ([] (start! port))
  ([p]
   (hyperfiddle.rcf/enable!)
   (reset! !port p)
   (reset! !url (examples/start! p))))

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
  ;; With the countdown page open (no `&user=` → you are "demo-user"): send
  ;; a server EVENT to that tab's flow; its render is pushed down its stream.
  ;; More in the countdown and live-counter controllers' comment blocks.
  (ds/connections) ; who can be reached: {component-id [user-id …]}
  (ds/send-event! "countdown1" "demo-user" "start" {})
  (ds/send-event! "live1" "demo-user" "INCREMENT" {:count 5 :state :displayed})

  ;; send-ui! pushes a hand-built fragment instead of an event; :broadcast
  ;; reaches every tab connected under the component-id.
  (ds/send-ui! "live1" "demo-user"
               [[:broadcast [:div#live1 [:h2 "Pushed from the REPL"]] {:d*/selector "#live1"}]]))
