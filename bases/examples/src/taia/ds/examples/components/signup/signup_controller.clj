(ns taia.ds.examples.components.signup.signup-controller
  "State machine for example 3.

   A transition can be a VECTOR of options; the first whose `:cond` passes
   (or has none) is taken. `:cond` is `(fn [ctx event-params] -> boolean)`
   and sees `:memory`, so validation is a guard, not an if in the action.

   `:submitted` handles `:INIT` with an empty map: a connected re-INIT or a
   reconnect must never fall through to the machine's 'event not found'
   warning, which silently keeps the old render."
  (:require
   [clojure.string :as str]))

(def blank-memory {:name "" :email ""})

(defn errors
  "Validation as data: a map of field -> message, empty when valid."
  [{:keys [name email]}]
  (cond-> {}
    (str/blank? (str name)) (assoc :name "Tell us your name")
    (not (re-find #"@" (str email))) (assoc :email "That does not look like an email")))

(defn- valid? [ctx _] (empty? (errors (:memory ctx))))

(def machine
  {:id      :signup
   :initial :editing
   :states  {:editing   {:on {:INIT   {:actions (fn [ctx _] (update ctx :memory #(merge blank-memory %)))}
                              :SUBMIT [{:target  :submitted
                                        :cond    valid?
                                        :actions (fn [ctx _] (update ctx :memory dissoc :errors))}
                                       {:actions (fn [ctx _] (assoc-in ctx [:memory :errors] (errors (:memory ctx))))}]}}
             :submitted {:on {:INIT  {}
                              :RESET {:target :editing :actions (fn [ctx _] (assoc ctx :memory blank-memory))}}}}})
