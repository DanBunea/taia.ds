(ns {{top/ns}}.{{main/ns}}.web.components.basics.counter-controller
  "State machine for the counter. One state, four events. A transition map
   without `:target` stays in the current state; `:actions` is
   `(fn [ctx event-params] -> ctx)` and is the only place state changes.

   `seed` merges rather than overwrites so a `:count` already present in the
   signal store survives a reconnect or a second INIT.

   The tests below: each action on its own, then the machine with
   `test-machine` — which event runs which action, and where it lands."
  (:require
   [hyperfiddle.rcf :refer [tests]]
   [taia.ds.state-machines.test-helpers :refer [test-machine]]))

(defn seed      [ctx _] (update-in ctx [:memory :count] #(or % 0)))
(defn increment [ctx _] (update-in ctx [:memory :count] inc))
(defn decrement [ctx _] (update-in ctx [:memory :count] dec))
(defn reset     [ctx _] (assoc-in ctx [:memory :count] 0))

(def machine
  {:id      :counter
   :initial :displayed
   :states  {:displayed {:on {:INIT      {:actions seed}
                              :INCREMENT {:actions increment}
                              :DECREMENT {:actions decrement}
                              :RESET     {:actions reset}}}}})

;;; the actions

(tests
 "seed: a fresh store starts the count at 0"
 (seed {:memory {}} {}) := {:memory {:count 0}}

 "seed: a count already in the store survives (a reconnect, a second INIT)"
 (seed {:memory {:count 7}} {}) := {:memory {:count 7}}

 "increment: +1"
 (increment {:memory {:count 2}} {}) := {:memory {:count 3}}

 "decrement: -1, below zero too"
 (decrement {:memory {:count 2}} {}) := {:memory {:count 1}}
 (decrement {:memory {:count 0}} {}) := {:memory {:count -1}}

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
               :DECREMENT {:target :displayed :trace [:decrement]}
               :RESET     {:target :displayed :trace [:reset]}}}
  machine)
 := :ok)
