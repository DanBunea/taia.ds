(ns taia.ds.examples.components.wizard.wizard
  "Example 4 — Wizard.

   The state is HIERARCHICAL: `[:editing :details]`, `[:editing :address]`,
   `[:editing :review]`, then `:saving` and `:done`. A vector state is what
   `:state` looks like in the signal store too (`[\"editing\",\"address\"]`),
   and the render fn branches on it.

   SUBMIT moves to `:saving`, whose `:invoke` runs synchronously inside the
   same request: `:on-done` lands in `:done`, `:on-error` goes back to the
   review step with the error in `:memory`. Type `fail` as the name to see
   the error path. RESET is declared at machine level (`:on`), so it works
   from any state."
  (:require
   [taia.ds.examples.inspect :as inspect]
   [taia.ds.datastar.interface :as ds]))

(defn- post [cid event]
  (str "@post('/ds/nds/wizard.wizard/" cid "/" event "')"))

(defn step-of
  "The leaf of a hierarchical state (`[:editing :address]` → `:address`)."
  [state]
  (if (vector? state) (last state) state))

(defn- steps [current]
  (into [:div.steps]
        (for [[k label] [[:details "1 Details"] [:address "2 Address"] [:review "3 Review"]]]
          [:span {:class (when (= k current) "active")} label])))

(defn- text-input [cid k label]
  [:div
   [:label {:for (str cid "-" (name k))} label]
   [:input {:id (str cid "-" (name k)) :type "text" "data-bind" (str cid "." (name k))}]])

(defn- error-line [memory]
  (when-let [e (:error memory)]
    [:p.error {:data-testid "error"} e]))

(defn- details [cid memory]
  (list (text-input cid :name "Your name (type 'fail' to make the save fail)")
        (error-line memory)
        [:div.row [:button {:type "button" :data-testid "next" "data-on:click" (post cid "next")} "Next"]]))

(defn- address [cid memory]
  (list (text-input cid :street "Street")
        (text-input cid :city "City")
        (error-line memory)
        [:div.row
         [:button.secondary {:type "button" "data-on:click" (post cid "back")} "Back"]
         [:button {:type "button" :data-testid "next" "data-on:click" (post cid "next")} "Next"]]))

(defn- review [cid {:keys [name street city] :as memory}]
  (list [:p [:strong name] [:br] street [:br] city]
        (error-line memory)
        [:div.row
         [:button.secondary {:type "button" "data-on:click" (post cid "back")} "Back"]
         [:button {:type "button" :data-testid "submit" "data-on:click" (post cid "submit")} "Submit"]]))

(defn- done [cid memory]
  (list [:p.ok {:data-testid "receipt"} (str "Saved. Receipt " (:receipt memory))]
        [:button.secondary {:type "button" "data-on:click" (post cid "reset")} "Start over"]))

(defn wizard [{:keys [component-id state memory] :as ctx}]
  (let [step (step-of state)]
    [:div.card {:id component-id :data-signals (ds/ctx->data-signals ctx)}
     [:h2 "Wizard"]
     (when (vector? state) (steps step))
     (case step
       :details (details component-id memory)
       :address (address component-id memory)
       :review  (review component-id memory)
       :done    (done component-id memory)
       [:p.muted (str "state: " (pr-str state))])
     [:p.muted "State: " [:code {:data-testid "state"} (pr-str state)]]
     (inspect/state-pre ctx)]))
