(ns user
  "The REPL's starting namespace (loaded automatically: development/src is on
   the :dev classpath). `dev` is required lazily, on the first call, so a test
   run does not load the application just to start.

     (start!)    ; the app on http://localhost:{{port}}
     (stop!)
     (restart!)
     (dev)       ; switch to the dev namespace")

(defn start!   [] ((requiring-resolve 'dev/start!)))
(defn stop!    [] ((requiring-resolve 'dev/stop!)))
(defn restart! [] ((requiring-resolve 'dev/restart!)))

(defn dev
  "Loads the dev namespace and switches the REPL to it."
  []
  (require 'dev)
  (in-ns 'dev))

(comment
  (start!)
  (stop!)
  (restart!)
  (dev)

  nil) ; repl entrypoint
