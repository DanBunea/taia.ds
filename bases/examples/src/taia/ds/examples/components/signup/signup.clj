(ns taia.ds.examples.components.signup.signup
  "Example 3 — Signup form.

   Form fields are CLIENT-owned state: each input has
   `data-bind=\"<component-id>.<key>\"`, so typing writes straight into the
   component's slice of the signal store and arrives in `:memory` on the
   next event without any per-keystroke request.

   SUBMIT is a guarded transition (see the controller): valid → `:submitted`,
   invalid → stay and record `:errors`. When there are errors the render fn
   returns an EFFECTS LIST instead of hiccup — a `:patch` of the form plus an
   `:execute-script` that focuses the first invalid field. Any render may
   return either shape."
  (:require
   [taia.ds.datastar.interface :as ds]))

(def fields
  [[:name "Name" "text"]
   [:email "Email" "email"]])

(defn- post [cid event]
  (str "@post('/ds/nds/signup.signup/" cid "/" event "')"))

(defn- input-id [cid k] (str cid "-" (name k)))

(defn- field [cid errors [k label type]]
  [:div
   [:label {:for (input-id cid k)} label]
   [:input {:id (input-id cid k) :type type "data-bind" (str cid "." (name k))}]
   (when-let [e (get errors k)]
     [:p.error {:data-testid (str "error-" (name k))} e])])

(defn- form [{:keys [component-id memory] :as ctx}]
  [:div.card {:id component-id :data-signals (ds/ctx->data-signals ctx)}
   [:h2 "Sign up"]
   (map #(field component-id (:errors memory) %) fields)
   [:div.row {:style {:margin-top "1rem"}}
    [:button {:type "button" :data-testid "submit" "data-on:click" (post component-id "submit")} "Create account"]]
   [:p.muted "Type, then submit. Nothing is sent while you type: the fields are signals, read on the server when the event fires."]])

(defn- success [{:keys [component-id memory] :as ctx}]
  [:div.card {:id component-id :data-signals (ds/ctx->data-signals ctx)}
   [:h2.ok {:data-testid "welcome"} (str "Welcome, " (:name memory) "!")]
   [:p (str "We would email " (:email memory) " if this were real.")]
   [:button.secondary {:type "button" "data-on:click" (post component-id "reset")} "Start over"]])

(defn- first-invalid [memory]
  (some (fn [[k]] (when (get-in memory [:errors k]) k)) fields))

(defn signup [{:keys [state component-id memory] :as ctx}]
  (if (= :submitted state)
    (success ctx)
    (if-let [k (first-invalid memory)]
      [[:patch (form ctx) {}]
       [:execute-script (str "document.getElementById('" (input-id component-id k) "').focus()")]]
      (form ctx))))
