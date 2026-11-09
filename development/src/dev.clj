(ns dev
  "Development entry point: the examples application plus an nREPL.

     bb dev                        ; or: clojure -M:dev -m dev
     http://localhost:7788/        ; examples index
     nREPL on 7789                 ; e.g. Clojure MCP clojure_eval with port 7789

   The HTTP wiring lives in `taia.ds.examples.core` (the `examples` base), which
   is also the reference for how an application base mounts the library."
  (:require
   [hyperfiddle.rcf]
   [nrepl.server :as nrepl]
   [taia.ds.datastar.interface :as ds]
   [taia.ds.examples.core :as examples]))

(def port 7788)
(def nrepl-port 7789)

(defonce !nrepl (atom nil))

(defn stop! []
  (examples/stop!)
  (when-let [n @!nrepl]
    (nrepl/stop-server n)
    (reset! !nrepl nil)))

(defn start! []
  (stop!)
  (hyperfiddle.rcf/enable!)
  (examples/start! port)
  (reset! !nrepl (nrepl/start-server :port nrepl-port))
  (spit ".nrepl-port" nrepl-port)
  (println (str "taia.ds examples on http://localhost:" port "/ ; nREPL on " nrepl-port)))

(comment
  ;; With /examples/live-counter open: push a fragment to every connected tab.
  (ds/send-ui! "live1" "demo-user"
               [[:broadcast [:div#live1 [:h2 "Pushed from the REPL"]] {:d*/selector "#live1"}]]))

(defn -main [& _]
  (start!)
  @(promise))
