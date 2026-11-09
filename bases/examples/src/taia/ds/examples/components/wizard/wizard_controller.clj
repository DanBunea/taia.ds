(ns taia.ds.examples.components.wizard.wizard-controller
  "State machine for example 4.

   Targets inside a nested state are RELATIVE: from `[:editing :details]`,
   `:target :address` means `[:editing :address]`. To leave the parent, give
   the full path as a vector: `:target [:saving]`.

   `:invoke` on a state runs as soon as the state is entered, in the same
   transition. Its `:src` gets the ctx (NOT the event params — capture those
   in a transition action first if you need them) and returns a value for
   `:on-done`'s action, or throws `ex-info` whose data goes to `:on-error`."
  (:require
   [clojure.string :as str]))

(def blank-memory {:name "" :street "" :city ""})

(defn- clear-error [ctx _] (update ctx :memory dissoc :error))
(defn- set-error [msg] (fn [ctx _] (assoc-in ctx [:memory :error] msg)))

(defn- name-ok? [ctx _] (not (str/blank? (str (get-in ctx [:memory :name])))))
(defn- address-ok? [ctx _]
  (let [{:keys [street city]} (:memory ctx)]
    (and (not (str/blank? (str street))) (not (str/blank? (str city))))))

(defn save!
  "The invoke: pretends to persist. Throws for the name 'fail' so the example
   shows `:on-error`."
  [ctx]
  (let [{:keys [name] :as memory} (:memory ctx)]
    (if (= "fail" (str/lower-case (str/trim (str name))))
      (throw (ex-info "save refused" {:message "The server refused to save (you typed 'fail')."}))
      {:receipt (format "W-%04d" (mod (Math/abs (hash (select-keys memory [:name :street :city]))) 10000))})))

(def machine
  {:id      :wizard
   :initial [:editing :details]
   :on      {:INIT  {}
             :RESET {:target [:editing :details] :actions (fn [ctx _] (assoc ctx :memory blank-memory))}}
   :states  {:editing {:states {:details {:on {:NEXT [{:target :address :cond name-ok? :actions clear-error}
                                                       {:actions (set-error "Name is required")}]}}
                                :address {:on {:BACK :details
                                               :NEXT [{:target :review :cond address-ok? :actions clear-error}
                                                      {:actions (set-error "Street and city are required")}]}}
                                :review  {:on {:BACK   :address
                                               :SUBMIT {:target [:saving]}}}}}
             :saving  {:invoke {:id       :save
                                :src      save!
                                :on-done  {:target  [:done]
                                           :actions (fn [ctx result] (-> ctx
                                                                         (update :memory dissoc :error)
                                                                         (assoc-in [:memory :receipt] (:receipt result))))}
                                :on-error {:target  [:editing :review]
                                           :actions (fn [ctx err] (assoc-in ctx [:memory :error] (:message err)))}}}
             :done    {}}})
