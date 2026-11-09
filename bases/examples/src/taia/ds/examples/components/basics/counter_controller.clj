(ns taia.ds.examples.components.basics.counter-controller
  "State machine for example 1. One state, four events. A transition map
   without `:target` stays in the current state; `:actions` is
   `(fn [ctx event-params] -> ctx)` and is the only place state changes.

   `seed` merges rather than overwrites so a `:count` already present in the
   signal store survives a reconnect or a second INIT.")

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
