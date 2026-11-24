(ns taia.ds.examples.components.live.countdown-controller
  "State machine for example 7, a connected component driven by server events.

   START launches a ticker that sends a TICK server event once a second with
   `ds/send-event!`, carrying the value to show: {:remaining 4}, then 3, … 0.
   Nothing is kept between events — a server event brings no request and no
   signals, so it starts from the machine's initial state, with its params —
   which is why TICK is handled at the machine level, from any state, and
   takes `:remaining` from its params. Its render is pushed down the tab's
   open stream; nothing here builds a patch by hand.

   `send-event!` is addressed to (component-id, user-id) — one tab's flow. It
   returns false once that tab's stream has closed, which is how the ticker
   knows to stop."
  (:require
   [taia.ds.datastar.interface :as ds]))

(def seconds
  "Where the countdown starts."
  5)

(def tick-ms
  "The gap between two TICKs."
  1000)

(defn start-ticker!
  "Counts down from `from` on a virtual thread: a TICK every `tick-ms`, with
   {:remaining from-1}, …, {:remaining 0}; stops early if the tab's stream
   closes."
  [component-id user-id from]
  (Thread/startVirtualThread
   (fn []
     (loop [n (dec from)]
       (when (>= n 0)
         (Thread/sleep (long tick-ms))
         (when (ds/send-event! component-id user-id "tick" {:remaining n})
           (recur (dec n))))))))

(defn- start [ctx _]
  (start-ticker! (:component-id ctx) (get-in ctx [:logged-user :user/id]) seconds)
  (assoc ctx :remaining seconds))

(defn- finished? [_ {:keys [remaining]}] (<= remaining 0))

(defn- tick [ctx {:keys [remaining]}] (assoc ctx :remaining remaining))

(def machine
  {:id      :countdown
   :initial :idle
   ;; from any state: a server event starts from :idle, with only its params
   :on      {:TICK [{:cond finished? :target :done :actions tick}
                    {:target :running :actions tick}]}
   :states  {:idle    {:on {:INIT  {:actions (fn [ctx _] (assoc ctx :remaining seconds))}
                            :START {:target :running :actions start}}}
             :running {}
             :done    {}}})

(comment
  ;; REPL, with the app running ((user/start!) or `bb dev`) and the page open:
  ;;   http://localhost:7788/ds/nds/home.home/home/init?example=countdown
  ;; Without `&user=…` the examples app signs you in as "demo-user" (see the
  ;; page footer); with `&user=alice`, use "alice" below instead.

  ;; who has an open stream, per component-id — check here when send-event!
  ;; answers false
  (ds/connections) ; => {"countdown1" ["demo-user"] …}

  ;; Every call below is a SERVER event on that user's flow: it starts from
  ;; the machine's initial state with the params given, and the render is
  ;; pushed down the stream. Each returns true, or false when the user has no
  ;; open stream.

  ;; show any value, the way the ticker does
  (ds/send-event! "countdown1" "demo-user" "tick" {:remaining 3})
  (ds/send-event! "countdown1" "demo-user" "tick" {:remaining 0}) ; → :done

  ;; start the countdown without a click (starts the ticker too)
  (ds/send-event! "countdown1" "demo-user" "start" {})

  ;; a user with no open stream
  (ds/send-event! "countdown1" "nobody" "start" {}) ; => false
  )
