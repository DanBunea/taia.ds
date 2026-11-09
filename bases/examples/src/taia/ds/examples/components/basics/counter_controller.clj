(ns taia.ds.examples.components.basics.counter-controller
  "State machine for example 1. One state, four events. A transition map
   without `:target` stays in the current state; `:actions` is
   `(fn [ctx event-params] -> ctx)` and is the only place state changes.

   INIT merges rather than overwrites so a `:count` already present in the
   signal store survives a reconnect or a second INIT.")

(def machine
  {:id      :counter
   :initial :displayed
   :states  {:displayed {:on {:INIT      {:actions (fn [ctx _] (update-in ctx [:memory :count] #(or % 0)))}
                              :INCREMENT {:actions (fn [ctx _] (update-in ctx [:memory :count] inc))}
                              :DECREMENT {:actions (fn [ctx _] (update-in ctx [:memory :count] dec))}
                              :RESET     {:actions (fn [ctx _] (assoc-in ctx [:memory :count] 0))}}}}})
