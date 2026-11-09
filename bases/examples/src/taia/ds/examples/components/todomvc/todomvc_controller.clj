(ns taia.ds.examples.components.todomvc.todomvc-controller
  "State machine for example 5. One state; every event is an action over
   `:memory`. Ids are allocated from `:nextId` so they survive deletes;
   `editingId` is nil when not editing (a JSON null in the store)."
  (:require
   [clojure.string :as str]))

(def blank-memory
  {:todos [] :nextId 1 :newTodo "" :filter "all" :editingId nil :editText ""})

(defn- param-id [params]
  (some-> (:id params) str parse-long))

(defn- update-todo [ctx id f]
  (update-in ctx [:memory :todos] (fn [todos] (mapv #(if (= id (:id %)) (f %) %) todos))))

(defn- remove-todo [ctx id]
  (update-in ctx [:memory :todos] (fn [todos] (vec (remove #(= id (:id %)) todos)))))

(defn- stop-editing [ctx]
  (-> ctx (assoc-in [:memory :editingId] nil) (assoc-in [:memory :editText] "")))

(defn- seed [ctx _] (update ctx :memory #(merge blank-memory %)))

(defn- add [ctx _]
  (let [text (str/trim (str (get-in ctx [:memory :newTodo])))]
    (if (str/blank? text)
      ctx
      (-> ctx
          (update-in [:memory :todos] (fnil conj []) {:id (get-in ctx [:memory :nextId]) :text text :completed false})
          (update-in [:memory :nextId] inc)
          (assoc-in [:memory :newTodo] "")))))

(defn- toggle [ctx params]
  (if-let [id (param-id params)] (update-todo ctx id #(update % :completed not)) ctx))

(defn- destroy [ctx params]
  (if-let [id (param-id params)] (remove-todo ctx id) ctx))

(defn- toggle-all [ctx _]
  (let [todos (get-in ctx [:memory :todos])
        all-done? (every? :completed todos)]
    (assoc-in ctx [:memory :todos] (mapv #(assoc % :completed (not all-done?)) todos))))

(defn- clear-completed [ctx _]
  (update-in ctx [:memory :todos] #(vec (remove :completed %))))

(defn- set-filter [ctx params]
  (assoc-in ctx [:memory :filter] (if (#{"all" "active" "completed"} (:filter params)) (:filter params) "all")))

(defn- edit [ctx params]
  (if-let [todo (some #(when (= (param-id params) (:id %)) %) (get-in ctx [:memory :todos]))]
    (-> ctx (assoc-in [:memory :editingId] (:id todo)) (assoc-in [:memory :editText] (:text todo)))
    ctx))

(defn- save-edit [ctx _]
  (let [{:keys [editingId editText]} (:memory ctx)
        text (str/trim (str editText))]
    (cond
      (nil? editingId)   ctx
      (str/blank? text)  (stop-editing (remove-todo ctx editingId))
      :else              (stop-editing (update-todo ctx editingId #(assoc % :text text))))))

(defn- cancel-edit [ctx _] (stop-editing ctx))

(def machine
  {:id      :todomvc
   :initial :displayed
   :states  {:displayed {:on {:INIT            {:actions seed}
                              :ADD             {:actions add}
                              :TOGGLE          {:actions toggle}
                              :DESTROY         {:actions destroy}
                              :TOGGLE_ALL      {:actions toggle-all}
                              :CLEAR_COMPLETED {:actions clear-completed}
                              :FILTER          {:actions set-filter}
                              :EDIT            {:actions edit}
                              :SAVE_EDIT       {:actions save-edit}
                              :CANCEL_EDIT     {:actions cancel-edit}}}}})
