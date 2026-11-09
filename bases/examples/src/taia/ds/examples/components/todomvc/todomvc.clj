(ns taia.ds.examples.components.todomvc.todomvc
  "Example 5 — TodoMVC, entirely in `:memory`.

   Everything the previous examples showed, together:

   * event parameters ride on the query string (`toggle?id=3`) and arrive as
     STRINGS in the action's second argument — parse them;
   * inputs are `data-bind` signals (`newTodo`, `editText`), camelCase so
     they are valid identifiers in Datastar expressions;
   * keyboard events: `data-on:keydown` with an expression that checks
     `evt.key` before posting;
   * one render fn draws the whole widget; the list, the footer and the
     editing row are plain functions of `:memory`."
  (:require
   [taia.ds.datastar.interface :as ds]))

(defn- post
  ([cid event] (post cid event nil))
  ([cid event query] (str "@post('/ds/nds/todomvc.todomvc/" cid "/" event (when query (str "?" query)) "')")))

(defn visible-todos [todos filter]
  (case filter
    "active"    (remove :completed todos)
    "completed" (filter :completed todos)
    todos))

(defn- todo-row [cid {:keys [id text completed]} editing?]
  [:li {:class (str (when completed "completed") (when editing? " editing"))
        :data-testid (str "todo-" id)}
   (if editing?
     [:input {:type "text" :autofocus true
              "data-bind" (str cid ".editText")
              "data-on:keydown" (str "evt.key==='Enter' && " (post cid "save_edit") "; "
                                     "evt.key==='Escape' && " (post cid "cancel_edit"))}]
     (list
      [:input {:type "checkbox" :checked completed :data-testid (str "toggle-" id)
               "data-on:change" (post cid "toggle" (str "id=" id))}]
      [:span.text {"data-on:dblclick" (post cid "edit" (str "id=" id))} text]
      [:button.secondary {:type "button" :data-testid (str "destroy-" id)
                          "data-on:click" (post cid "destroy" (str "id=" id))} "×"]))])

(defn- filter-link [cid current f label]
  [:a {:class (when (= f current) "selected") :data-testid (str "filter-" f)
       "data-on:click" (post cid "filter" (str "filter=" f))} label])

(defn todomvc [{:keys [component-id memory] :as ctx}]
  (let [{:keys [todos filter editingId]} memory
        todos     (or todos [])
        remaining (count (remove :completed todos))
        completed (- (count todos) remaining)]
    [:div.card {:id component-id :data-signals (ds/ctx->data-signals ctx)}
     [:h2 "todos"]
     [:div.row
      (when (seq todos)
        [:input {:type "checkbox" :checked (zero? remaining) :title "Toggle all" :data-testid "toggle-all"
                 "data-on:change" (post component-id "toggle_all")}])
      [:input {:type "text" :placeholder "What needs to be done?" :data-testid "new-todo" :style {:flex 1}
               "data-bind" (str component-id ".newTodo")
               "data-on:keydown" (str "evt.key==='Enter' && " (post component-id "add"))}]]
     (into [:ul.todos]
           (for [t (visible-todos todos (or filter "all"))]
             (todo-row component-id t (= (:id t) editingId))))
     (when (seq todos)
       [:div.row {:style {:justify-content "space-between" :margin-top ".75rem"}}
        [:span.muted {:data-testid "remaining"} (str remaining (if (= 1 remaining) " item left" " items left"))]
        [:span.filters
         (filter-link component-id filter "all" "All")
         (filter-link component-id filter "active" "Active")
         (filter-link component-id filter "completed" "Completed")]
        (when (pos? completed)
          [:button.secondary {:type "button" :data-testid "clear-completed"
                              "data-on:click" (post component-id "clear_completed")} "Clear completed"])])]))
