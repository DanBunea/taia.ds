(ns taia.ds.examples.components.live.countdown-controller
  "State machine for example 7. The countdown lives in the connected
   component's FLOW: its `:controller` proc holds the ctx between events, so
   `:remaining` is plain top-level ctx — no atom, no signal round-trip.

   START launches a ticker that sends a TICK server event once a second with
   `ds/send-event!`. Each TICK transitions from the ctx the flow holds, and
   its render is pushed down the tab's open stream. Nothing here builds a
   patch by hand.

   Two things worth knowing:

   * `send-event!` is addressed to (component-id, user-id) — one tab's flow.
     It returns false once that tab's stream has closed, which is how the
     ticker knows to stop.
   * `:remaining` is server-owned, so it lives OUTSIDE `:memory`: a click's
     signals merge into the held `:memory`, and a stale echo of a
     server-owned value there would overwrite the server's."
  (:require
   [taia.ds.datastar.interface :as ds]))

(def seconds
  "Where the countdown starts."
  5)

(def tick-ms
  "The gap between two TICKs."
  1000)

(defn start-ticker!
  "Sends `n` TICK events to the tab's flow, `tick-ms` apart, on a virtual
   thread; stops early if the tab's stream closes."
  [component-id user-id n]
  (Thread/startVirtualThread
   (fn []
     (loop [n n]
       (when (pos? n)
         (Thread/sleep (long tick-ms))
         (when (ds/send-event! component-id user-id "tick" {})
           (recur (dec n))))))))

(defn- start [ctx _]
  (start-ticker! (:component-id ctx) (get-in ctx [:logged-user :user/id]) seconds)
  (assoc ctx :remaining seconds))

(defn- last-tick? [ctx _] (<= (or (:remaining ctx) 0) 1))

(defn- tick [ctx _] (update ctx :remaining dec))

(def machine
  {:id      :countdown
   :initial :idle
   :states  {:idle    {:on {:INIT  {:actions (fn [ctx _] (assoc ctx :remaining seconds))}
                            :START {:target :running :actions start}}}
             :running {:on {:TICK [{:cond last-tick? :target :done
                                    :actions (fn [ctx _] (assoc ctx :remaining 0))}
                                   {:actions tick}]}}
             :done    {:on {:START {:target :running :actions start}}}}})

(comment
  ;; REPL, with the app running ((user/start!) or `bb dev`) and the page open:
  ;;   http://localhost:7788/ds/nds/home.home/home/init?example=countdown
  ;; Without `&user=…` the examples app signs you in as "demo-user" (see the
  ;; page footer); with `&user=alice`, use "alice" below instead.

  ;; who has an open stream, per component-id — check here when send-event!
  ;; answers false
  (ds/connections) ; => {"countdown1" ["demo-user"] …}

  ;; Every call below is a SERVER event on that user's flow: it transitions
  ;; from the ctx the flow holds, and the render is pushed down the stream.
  ;; Each returns true, or false when the user has no open stream.

  ;; start the countdown without a click (starts the ticker too)
  (ds/send-event! "countdown1" "demo-user" "start" {})

  ;; tick by hand: from :running, each one takes a second off
  (ds/send-event! "countdown1" "demo-user" "tick" {})

  ;; an event the current state does not handle changes nothing — the same
  ;; render is pushed again
  (ds/send-event! "countdown1" "demo-user" "init" {})

  ;; a user with no open stream
  (ds/send-event! "countdown1" "nobody" "start" {}) ; => false
  )
