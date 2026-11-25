(ns {{top/ns}}.{{main/ns}}.web.components.live.live-counter-controller
  "State machine for the live counter. The count is the tab's: it lives in `:memory`
   and round-trips through the tab's signals. Nothing is kept on the server.

   * A click is a one-shot POST to /ds/nds/ with the tab's signals, so
     INCREMENT starts from the count in its `:memory`.
   * A server event (`send-event!`, e.g. from the REPL) has no request and
     no signals, so it brings the count as its params, and INCREMENT starts
     from that. Its render is pushed down the tab's stream; the render's
     `data-signals` update the tab's store, so the next click carries it."
  (:require
   [hyperfiddle.rcf :refer [tests]]
   [taia.ds.datastar.interface :as ds]
   [taia.ds.state-machines.interface.test-helpers :refer [test-machine]]))

(defn- seed [ctx _] (update-in ctx [:memory :count] #(or % 0)))

(defn- increment
  "+1 on the count the event brings: its params' (a server event), else its
   memory's (a click)."
  [ctx {:keys [count]}]
  (assoc-in ctx [:memory :count] (inc (or count (get-in ctx [:memory :count]) 0))))

(defn- reset [ctx _] (assoc-in ctx [:memory :count] 0))

(def machine
  {:id      :live-counter
   :initial :displayed
   :states  {:displayed {:on {:INIT      {:actions seed}
                              :INCREMENT {:actions increment}
                              :RESET     {:actions reset}}}}})

;;; the actions

(tests
 "seed: a fresh store starts the count at 0"
 (seed {:memory {}} {}) := {:memory {:count 0}}

 "seed: a count already in the store survives (a reconnect, a second INIT)"
 (seed {:memory {:count 7}} {}) := {:memory {:count 7}}

 "increment, a click: +1 on the count in the tab's memory"
 (increment {:memory {:count 2}} {}) := {:memory {:count 3}}

 "increment, a server event: +1 on the count its params bring (it has no signals)"
 (increment {:memory {}} {:count 2}) := {:memory {:count 3}}

 "increment: the params' count wins over the memory's"
 (increment {:memory {:count 9}} {:count 2}) := {:memory {:count 3}}

 "increment: no count anywhere counts from 0"
 (increment {:memory {}} {}) := {:memory {:count 1}}

 "reset: back to 0"
 (reset {:memory {:count 5}} {}) := {:memory {:count 0}})

;;; the machine: per state and event, the target and the trace of actions.
;;; test-machine runs no action (their behaviour is tested above), it only
;;; records their names.

(tests
 "every event stays in :displayed and runs its action"
 (test-machine
  {:displayed {:INIT      {:target :displayed :trace [:seed]}
               :INCREMENT {:target :displayed :trace [:increment]}
               :RESET     {:target :displayed :trace [:reset]}}}
  machine)
 := :ok)

;; From the REPL: a server event into an open tab's flow is `ds/send-event!`
;; — component-id, user-id, event, params. It has no signals, so it sends the
;; memory itself as params.
(comment
  ;; With the app running ((user/start!) or `bb dev`) and the page open:
  ;;   http://localhost:{{port}}/ds/nds/home.home/home/init?example=live-counter
  ;; That tab is "demo-user" (no `&user=`).
  (ds/connections) ; => {"live1" ["demo-user"] …}

  (ds/send-event! "live1" "demo-user" "increment" {:count 2}) ; the tab shows 3
  (ds/send-event! "live1" "demo-user" "reset" {})             ; back to 0
  )
