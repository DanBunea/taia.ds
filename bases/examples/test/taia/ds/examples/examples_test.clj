(ns taia.ds.examples.examples-test
  "The examples, driven through the real pipeline (discovery → FSM → render)
   with no HTTP. Each block is also the executable spec the matching
   docs/examples article refers to.

   `run`/`step` return {:memory :state :html :ctx}. The assertions compare that
   whole map with RCF's `_` wildcard on the parts a case is not about — so an
   assertion reads as \"after this event the signal store is exactly this\",
   and a stray key fails it.

   Every case writes where it starts from literally (a `{:memory :state}`
   store, as the browser would send it) instead of naming it elsewhere or
   chaining on the previous case's result; when a case asserts several things
   about one event, each assertion runs that event again. Each block has a
   small helper that runs one event of its example from such a store."
  (:require
   [clojure.string :as str]
   [hyperfiddle.rcf :refer [tests]]
   [taia.ds.datastar.interface :as ds]
   [spy.core :as spy]
   [taia.ds.examples.components.live.countdown-controller :as countdown]
   [taia.ds.examples.components.live.live-counter-controller :as live]
   [taia.ds.examples.components.weather.weather-api :as weather-api]
   [taia.ds.examples.test-support :as ts :refer [run step testid-text]]))

(tests
 (defn- home-init [params]
   (run "home.home" "home" {:params params} "init"))

 "0 home: the page is a component — INIT with no example is the index"
 (home-init nil) := {:memory {:user "tester"} :state :index :html _ :ctx _}
 (-> (home-init nil)
     :html
     ts/extract-post-urls)
 := []

 "0 home: a known example lands in :example; the resolved entry is derived (ctx :selected), the name is remembered (memory)"
 (home-init {:example "counter"}) := {:memory {:user "tester" :example "counter"} :state :example :html _ :ctx _}
 (-> (home-init {:example "counter"})
     :ctx
     :selected
     (select-keys [:cid :component]))
 := {:cid "counter1" :component "basics.counter"}

 "0 home: the mount fires the child's INIT with the page-level user signal"
 (-> (home-init {:example "counter"})
     :html
     ts/extract-post-urls)
 := ["/ds/nds/basics.counter/counter1/init?user="]

 "0 home: the page's own state viewer shows where the page is"
 (-> (home-init {:example "counter"})
     :html
     (testid-text "home-inspect")
     (str/includes? ":state :example"))
 := true

 "0 home: an unknown example lands in :not-found"
 (home-init {:example "nope"})
 := {:memory {:user "tester" :example "nope"} :state :not-found :html _ :ctx _})

(tests
 (defn- counter-event [from event]
   (step "basics.counter" "counter1" from event))

 "1 counter: INIT seeds 0"
 (counter-event {} "init") := {:memory {:count 0} :state :displayed :html _ :ctx _}
 (-> (counter-event {} "init")
     :html
     (testid-text "count"))
 := "0"

 "1 counter: a count already in the store survives INIT (reconnect / second init)"
 (counter-event {:memory {:count 7}} "init")
 := {:memory {:count 7} :state :displayed :html _ :ctx _}

 "1 counter: INCREMENT, DECREMENT and RESET change the count in the store"
 (counter-event {:memory {:count 2} :state :displayed} "increment") := {:memory {:count 3} :state :displayed :html _ :ctx _}
 (counter-event {:memory {:count 2} :state :displayed} "decrement") := {:memory {:count 1} :state :displayed :html _ :ctx _}
 (counter-event {:memory {:count 2} :state :displayed} "reset") := {:memory {:count 0} :state :displayed :html _ :ctx _}

 "1 counter: increments accumulate through the round-tripped store"
 (-> {:memory {:count 0} :state :displayed}
     (counter-event "increment")
     (counter-event "increment"))
 := {:memory {:count 2} :state :displayed :html _ :ctx _}

 "1 counter: the render shows the count, and the state viewer under every
  example prints exactly what round-trips"
 (-> (counter-event {:memory {:count 1} :state :displayed} "increment")
     :html
     (testid-text "count"))
 := "2"
 (-> (counter-event {:memory {:count 1} :state :displayed} "increment")
     :html
     (testid-text "inspect")
     (str/includes? "{:state :displayed, :memory {:count 2}}"))
 := true

 "1 counter: every button posts to this component's own route"
 (-> (counter-event {} "init")
     :html
     ts/extract-post-urls
     set)
 := #{"/ds/nds/basics.counter/counter1/decrement"
      "/ds/nds/basics.counter/counter1/increment"
      "/ds/nds/basics.counter/counter1/reset"})

(tests
 (defn- server-event
   "A server event (what `send-event!` sends) through a
    fresh live-counter flow: no request, no signals — only `params`."
   [event params]
   (let [[cf _] (ts/connect "live.live-counter" "live1" {})]
     (try
       (ts/server-event cf event params)
       (finally
         (ds/stop-component-flow! cf)))))

 "2 live counter: INIT seeds the count at 0, in :memory"
 (run "live.live-counter" "live1" {} "init") := {:memory {:count 0} :state :displayed :html _ :ctx _}

 "2 live counter: a click starts from the count in the tab's signals"
 (run "live.live-counter" "live1" {:memory {:count 1} :state :displayed} "increment")
 := {:memory {:count 2} :state :displayed :html _ :ctx _}
 (-> (run "live.live-counter" "live1" {:memory {:count 1} :state :displayed} "increment")
     :html
     (testid-text "count"))
 := "2"

 "2 live counter: a server event has no signals, so it brings the count as
  its params: (ds/send-event! \"live1\" user \"increment\" {:count 2})"
 (server-event "increment" {:count 2}) := {:memory {:count 3} :state :displayed :html _ :ctx _}
 (-> (server-event "increment" {:count 2})
     :html
     (testid-text "count"))
 := "3"

 "2 live counter: the render carries the count in data-signals, so a render
  pushed down the stream updates the tab's store for its next click"
 (-> (server-event "increment" {:count 2})
     :html
     second
     :data-signals
     (str/includes? "\"count\":3"))
 := true

 "2 live counter: RESET puts the count back to 0, from a click or a server event"
 (run "live.live-counter" "live1" {:memory {:count 3} :state :displayed} "reset")
 := {:memory {:count 0} :state :displayed :html _ :ctx _}
 (server-event "reset" {}) := {:memory {:count 0} :state :displayed :html _ :ctx _})

(tests
 (defn- signup-event [from event]
   (step "signup.signup" "signup1" from event))
 (defn- effect
   "The payload of the first `kind` effect in an effects list."
   [effects kind]
   (some (fn [[k payload]] (when (= k kind) payload)) effects))

 "3 signup: INIT seeds a blank form in :editing"
 (signup-event {} "init") := {:memory {:name "" :email ""} :state :editing :html _ :ctx _}

 "3 signup: invalid submit stays in :editing, records errors, returns an effects list that focuses the first invalid field"
 (signup-event {:memory {:name "" :email ""} :state :editing} "submit")
 := {:memory {:name "" :email "" :errors {:name "Tell us your name" :email "That does not look like an email"}}
     :state  :editing
     :html   [[:patch _] [:execute-script _]]
     :ctx    _}
 (-> (signup-event {:memory {:name "" :email ""} :state :editing} "submit")
     :html
     (effect :execute-script)
     (str/includes? "signup1-name"))
 := true
 (-> (signup-event {:memory {:name "" :email ""} :state :editing} "submit")
     :html
     (effect :patch)
     (testid-text "error-name"))
 := "Tell us your name"

 "3 signup: a valid submit moves to :submitted and drops :errors (sent back as null, so the client deletes it)"
 (signup-event {:memory {:name "Ada" :email "ada@example.com" :errors {:name "x"}} :state :editing} "submit")
 := {:memory {:name "Ada" :email "ada@example.com" :errors nil} :state :submitted :html _ :ctx _}
 (-> (signup-event {:memory {:name "Ada" :email "ada@example.com" :errors {:name "x"}} :state :editing} "submit")
     :memory
     (contains? :errors))
 := true
 (-> (signup-event {:memory {:name "Ada" :email "ada@example.com" :errors {:name "x"}} :state :editing} "submit")
     :html
     (testid-text "welcome"))
 := "Welcome, Ada!"

 "3 signup: INIT in :submitted is a no-op (the re-INIT hatch)"
 (signup-event {:memory {:name "Ada" :email "ada@example.com"} :state :submitted} "init")
 := {:memory {:name "Ada" :email "ada@example.com"} :state :submitted :html _ :ctx _}

 "3 signup: RESET returns to a blank form"
 (signup-event {:memory {:name "Ada" :email "ada@example.com"} :state :submitted} "reset")
 := {:memory {:name "" :email ""} :state :editing :html _ :ctx _})

(tests
 (defn- wizard-event [from event]
   (step "wizard.wizard" "wizard1" from event))

 "4 wizard: INIT seeds nothing — the form renders missing fields as \"\" and data-bind fills them in"
 (wizard-event {} "init") := {:memory {} :state [:editing :details] :html _ :ctx _}

 "4 wizard: a guard — NEXT without a name stays on :details with an error"
 (wizard-event {:memory {} :state [:editing :details]} "next")
 := {:memory {:error "Name is required"} :state [:editing :details] :html _ :ctx _}

 "4 wizard: NEXT with a name moves to :address; :error was removed by the
  guard's action, so it is sent back as nil (a JSON null deletes the signal)"
 (wizard-event {:memory {:name "Ada" :error "Name is required"} :state [:editing :details]} "next")
 := {:memory {:name "Ada" :error nil} :state [:editing :address] :html _ :ctx _}

 "4 wizard: a relative target — BACK from :address returns to :details"
 (wizard-event {:memory {:name "Ada"} :state [:editing :address]} "back")
 := {:memory {:name "Ada"} :state [:editing :details] :html _ :ctx _}

 "4 wizard: NEXT from :address moves to :review"
 (wizard-event {:memory {:name "Ada" :street "1 Loop" :city "Oxford"} :state [:editing :address]} "next")
 := {:memory {:name "Ada" :street "1 Loop" :city "Oxford"} :state [:editing :review] :html _ :ctx _}

 "4 wizard: SUBMIT enters :saving, the invoke runs in the same request, on-done lands in :done with a receipt"
 ;; the receipt is derived from the saved fields, so the same form always gets W-9678
 (wizard-event {:memory {:name "Ada" :street "1 Loop" :city "Oxford"} :state [:editing :review]} "submit")
 := {:memory {:name "Ada" :street "1 Loop" :city "Oxford" :receipt "W-9678"} :state :done :html _ :ctx _}
 (-> (wizard-event {:memory {:name "Ada" :street "1 Loop" :city "Oxford"} :state [:editing :review]} "submit")
     :memory
     :receipt
     (str/starts-with? "W-"))
 := true
 (-> (wizard-event {:memory {:name "Ada" :street "1 Loop" :city "Oxford"} :state [:editing :review]} "submit")
     :html
     (testid-text "receipt"))
 := "Saved. Receipt W-9678"

 "4 wizard: on-error returns to the review step with the message"
 (wizard-event {:memory {:name "fail" :street "s" :city "c"} :state [:editing :review]} "submit")
 := {:memory {:name "fail" :street "s" :city "c" :error "The server refused to save (you typed 'fail')."}
     :state  [:editing :review]
     :html   _
     :ctx    _}

 "4 wizard: RESET is machine-wide"
 ;; :receipt was removed by RESET, so the pipeline sends it back as nil → the
 ;; client receives a JSON null and deletes the signal (assoc-removed-signal-nils).
 (wizard-event {:memory {:name "Ada" :street "1 Loop" :city "Oxford" :receipt "W-1"} :state :done} "reset")
 := {:memory {:name "" :street "" :city "" :receipt nil} :state [:editing :details] :html _ :ctx _})

(tests
 (defn- todos-event
   ([from event] (todos-event from event nil))
   ([from event params] (step "todomvc.todomvc" "todos1" from event params)))

 "5 todomvc: INIT seeds an empty list"
 (todos-event {} "init")
 := {:memory {:todos [] :nextId 1 :newTodo "" :filter "all" :editingId nil :editText ""}
     :state  :displayed
     :html   _
     :ctx    _}

 "5 todomvc: add reads the bound newTodo signal, trims it and clears it; ids come from nextId"
 (todos-event {:memory {:todos [] :nextId 1 :newTodo "  buy milk " :filter "all" :editingId nil :editText ""}
               :state  :displayed}
              "add")
 := {:memory {:todos [{:id 1 :text "buy milk" :completed false}]
              :nextId 2 :newTodo "" :filter "all" :editingId nil :editText ""}
     :state  :displayed
     :html   _
     :ctx    _}
 (todos-event {:memory {:todos [{:id 1 :text "buy milk" :completed false}]
                        :nextId 2 :newTodo "walk" :filter "all" :editingId nil :editText ""}
               :state  :displayed}
              "add")
 := {:memory {:todos [{:id 1 :text "buy milk" :completed false}
                      {:id 2 :text "walk" :completed false}]
              :nextId 3 :newTodo "" :filter "all" :editingId nil :editText ""}
     :state  :displayed
     :html   _
     :ctx    _}

 "5 todomvc: a blank add is a no-op — nothing added, the input is left as typed"
 (todos-event {:memory {:todos [{:id 1 :text "buy milk" :completed false}]
                        :nextId 2 :newTodo "   " :filter "all" :editingId nil :editText ""}
               :state  :displayed}
              "add")
 := {:memory {:todos [{:id 1 :text "buy milk" :completed false}]
              :nextId 2 :newTodo "   " :filter "all" :editingId nil :editText ""}
     :state  :displayed
     :html   _
     :ctx    _}

 "5 todomvc: query-string params arrive as strings and are parsed — toggle ?id=1"
 (todos-event {:memory {:todos [{:id 1 :text "buy milk" :completed false}
                                {:id 2 :text "walk" :completed false}]
                        :nextId 3 :newTodo "" :filter "all" :editingId nil :editText ""}
               :state  :displayed}
              "toggle"
              {:id "1"})
 := {:memory {:todos [{:id 1 :text "buy milk" :completed true}
                      {:id 2 :text "walk" :completed false}]
              :nextId 3 :newTodo "" :filter "all" :editingId nil :editText ""}
     :state  :displayed
     :html   _
     :ctx    _}
 (-> (todos-event {:memory {:todos [{:id 1 :text "buy milk" :completed false}
                                    {:id 2 :text "walk" :completed false}]
                            :nextId 3 :newTodo "" :filter "all" :editingId nil :editText ""}
                   :state  :displayed}
                  "toggle"
                  {:id "1"})
     :html
     (testid-text "remaining"))
 := "1 item left"

 "5 todomvc: the active filter shows only the open todos"
 (todos-event {:memory {:todos [{:id 1 :text "buy milk" :completed true}
                                {:id 2 :text "walk" :completed false}]
                        :nextId 3 :newTodo "" :filter "all" :editingId nil :editText ""}
               :state  :displayed}
              "filter"
              {:filter "active"})
 := {:memory {:todos [{:id 1 :text "buy milk" :completed true}
                      {:id 2 :text "walk" :completed false}]
              :nextId 3 :newTodo "" :filter "active" :editingId nil :editText ""}
     :state  :displayed
     :html   _
     :ctx    _}
 (-> (todos-event {:memory {:todos [{:id 1 :text "buy milk" :completed true}
                                    {:id 2 :text "walk" :completed false}]
                            :nextId 3 :newTodo "" :filter "all" :editingId nil :editText ""}
                   :state  :displayed}
                  "filter"
                  {:filter "active"})
     :html
     (testid-text "todo-1"))
 := nil
 (-> (todos-event {:memory {:todos [{:id 1 :text "buy milk" :completed true}
                                    {:id 2 :text "walk" :completed false}]
                            :nextId 3 :newTodo "" :filter "all" :editingId nil :editText ""}
                   :state  :displayed}
                  "filter"
                  {:filter "active"})
     :html
     (testid-text "todo-2")
     some?)
 := true

 "5 todomvc: the completed filter shows only the completed todos"
 (todos-event {:memory {:todos [{:id 1 :text "buy milk" :completed true}
                                {:id 2 :text "walk" :completed false}]
                        :nextId 3 :newTodo "" :filter "all" :editingId nil :editText ""}
               :state  :displayed}
              "filter"
              {:filter "completed"})
 := {:memory {:todos [{:id 1 :text "buy milk" :completed true}
                      {:id 2 :text "walk" :completed false}]
              :nextId 3 :newTodo "" :filter "completed" :editingId nil :editText ""}
     :state  :displayed
     :html   _
     :ctx    _}
 (-> (todos-event {:memory {:todos [{:id 1 :text "buy milk" :completed true}
                                    {:id 2 :text "walk" :completed false}]
                            :nextId 3 :newTodo "" :filter "all" :editingId nil :editText ""}
                   :state  :displayed}
                  "filter"
                  {:filter "completed"})
     :html
     (testid-text "todo-1")
     some?)
 := true
 (-> (todos-event {:memory {:todos [{:id 1 :text "buy milk" :completed true}
                                    {:id 2 :text "walk" :completed false}]
                            :nextId 3 :newTodo "" :filter "all" :editingId nil :editText ""}
                   :state  :displayed}
                  "filter"
                  {:filter "completed"})
     :html
     (testid-text "todo-2"))
 := nil

 "5 todomvc: an unknown filter falls back to all"
 (todos-event {:memory {:todos [{:id 1 :text "buy milk" :completed true}
                                {:id 2 :text "walk" :completed false}]
                        :nextId 3 :newTodo "" :filter "active" :editingId nil :editText ""}
               :state  :displayed}
              "filter"
              {:filter "bogus"})
 := {:memory {:todos [{:id 1 :text "buy milk" :completed true}
                      {:id 2 :text "walk" :completed false}]
              :nextId 3 :newTodo "" :filter "all" :editingId nil :editText ""}
     :state  :displayed
     :html   _
     :ctx    _}

 "5 todomvc: destroy ?id=2 removes that todo"
 (todos-event {:memory {:todos [{:id 1 :text "buy milk" :completed true}
                                {:id 2 :text "walk" :completed false}]
                        :nextId 3 :newTodo "" :filter "all" :editingId nil :editText ""}
               :state  :displayed}
              "destroy"
              {:id "2"})
 := {:memory {:todos [{:id 1 :text "buy milk" :completed true}]
              :nextId 3 :newTodo "" :filter "all" :editingId nil :editText ""}
     :state  :displayed
     :html   _
     :ctx    _}

 "5 todomvc: toggle_all completes every todo"
 (todos-event {:memory {:todos [{:id 1 :text "buy milk" :completed true}
                                {:id 2 :text "walk" :completed false}]
                        :nextId 3 :newTodo "" :filter "all" :editingId nil :editText ""}
               :state  :displayed}
              "toggle_all")
 := {:memory {:todos [{:id 1 :text "buy milk" :completed true}
                      {:id 2 :text "walk" :completed true}]
              :nextId 3 :newTodo "" :filter "all" :editingId nil :editText ""}
     :state  :displayed
     :html   _
     :ctx    _}

 "5 todomvc: clear_completed removes the completed todos"
 (todos-event {:memory {:todos [{:id 1 :text "buy milk" :completed true}
                                {:id 2 :text "walk" :completed false}]
                        :nextId 3 :newTodo "" :filter "all" :editingId nil :editText ""}
               :state  :displayed}
              "clear_completed")
 := {:memory {:todos [{:id 2 :text "walk" :completed false}]
              :nextId 3 :newTodo "" :filter "all" :editingId nil :editText ""}
     :state  :displayed
     :html   _
     :ctx    _}

 "5 todomvc: edit ?id=2 copies the todo's text into editingId/editText"
 (todos-event {:memory {:todos [{:id 1 :text "buy milk" :completed false}
                                {:id 2 :text "walk" :completed false}]
                        :nextId 3 :newTodo "" :filter "all" :editingId nil :editText ""}
               :state  :displayed}
              "edit"
              {:id "2"})
 := {:memory {:todos [{:id 1 :text "buy milk" :completed false}
                      {:id 2 :text "walk" :completed false}]
              :nextId 3 :newTodo "" :filter "all" :editingId 2 :editText "walk"}
     :state  :displayed
     :html   _
     :ctx    _}

 "5 todomvc: save_edit writes the bound editText back"
 (todos-event {:memory {:todos [{:id 1 :text "buy milk" :completed false}
                                {:id 2 :text "walk" :completed false}]
                        :nextId 3 :newTodo "" :filter "all" :editingId 2 :editText "walk the dog"}
               :state  :displayed}
              "save_edit")
 := {:memory {:todos [{:id 1 :text "buy milk" :completed false}
                      {:id 2 :text "walk the dog" :completed false}]
              :nextId 3 :newTodo "" :filter "all" :editingId nil :editText ""}
     :state  :displayed
     :html   _
     :ctx    _}

 "5 todomvc: a blank save deletes the todo"
 (todos-event {:memory {:todos [{:id 1 :text "buy milk" :completed false}
                                {:id 2 :text "walk" :completed false}]
                        :nextId 3 :newTodo "" :filter "all" :editingId 2 :editText " "}
               :state  :displayed}
              "save_edit")
 := {:memory {:todos [{:id 1 :text "buy milk" :completed false}]
              :nextId 3 :newTodo "" :filter "all" :editingId nil :editText ""}
     :state  :displayed
     :html   _
     :ctx    _}

 "5 todomvc: cancel_edit leaves the todos as they were"
 (todos-event {:memory {:todos [{:id 1 :text "buy milk" :completed false}
                                {:id 2 :text "walk" :completed false}]
                        :nextId 3 :newTodo "" :filter "all" :editingId 2 :editText "walk"}
               :state  :displayed}
              "cancel_edit")
 := {:memory {:todos [{:id 1 :text "buy milk" :completed false}
                      {:id 2 :text "walk" :completed false}]
              :nextId 3 :newTodo "" :filter "all" :editingId nil :editText ""}
     :state  :displayed
     :html   _
     :ctx    _})

(tests
 ;; What the stubbed Open-Meteo lookup answers in every case of this block.
 (def fake-weather
   {:place "Oslo, Norway" :temperature -1.4 :wind 11.0 :condition "Light snow" :observed-at "2026-11-09T15:00"})
 (defn- weather-event
   ([from event] (weather-event from event nil))
   ([from event params] (step "weather.weather" "weather1" from event params)))

 "6 weather: INIT renders the skeleton and does NOT fetch; the skeleton's data-init posts LOAD"
 (with-redefs [weather-api/fetch-current-weather (spy/spy (fn [_] fake-weather))]
   (weather-event {} "init") := {:memory {:city "Barcelona"} :state :loading :html _ :ctx _}
   (spy/not-called? weather-api/fetch-current-weather) := true
   (-> (weather-event {} "init")
       :html
       (testid-text "loading"))
   := "…Loading the weather in Barcelona…"
   (-> (weather-event {} "init")
       :html
       ts/extract-post-urls)
   := ["/ds/nds/weather.weather/weather1/load"])

 "6 weather: LOAD enters :fetching, the invoke runs in the same request, on-done lands in :loaded with the weather in ctx (not :memory)"
 (with-redefs [weather-api/fetch-current-weather (spy/spy (fn [_] fake-weather))]
   (weather-event {:memory {:city "Barcelona"} :state :loading} "load")
   := {:memory {:city "Barcelona"} :state :loaded :html _ :ctx _}
   (spy/called-once-with? weather-api/fetch-current-weather "Barcelona") := true
   (-> (weather-event {:memory {:city "Barcelona"} :state :loading} "load")
       :ctx
       :weather)
   := fake-weather
   (-> (weather-event {:memory {:city "Barcelona"} :state :loading} "load")
       :html
       (testid-text "temperature"))
   := "-1.4 °C"
   (-> (weather-event {:memory {:city "Barcelona"} :state :loading} "load")
       :html
       (testid-text "place"))
   := "Oslo, Norway")

 "6 weather: SEARCH captures ?city= into :memory before the invoke reads it"
 (with-redefs [weather-api/fetch-current-weather (spy/spy (fn [_] fake-weather))]
   (weather-event {:memory {:city "Barcelona"} :state :loaded} "search" {:city "Oslo"})
   := {:memory {:city "Oslo"} :state :loaded :html _ :ctx _}
   (spy/called-once-with? weather-api/fetch-current-weather "Oslo") := true)

 "6 weather: SEARCH without the param — the bound input wins"
 (with-redefs [weather-api/fetch-current-weather (spy/spy (fn [_] fake-weather))]
   (weather-event {:memory {:city "Tokyo"} :state :loaded} "search")
   := {:memory {:city "Tokyo"} :state :loaded :html _ :ctx _}
   (spy/called-once-with? weather-api/fetch-current-weather "Tokyo") := true)

 "6 weather: a refused lookup lands in :failed with the message; the error is in ctx"
 (with-redefs [weather-api/fetch-current-weather (fn [city] (throw (ex-info "nope" {:message (str "No place called '" city "' was found.")})))]
   (weather-event {:memory {:city "Nowhere"} :state :loaded} "search")
   := {:memory {:city "Nowhere"} :state :failed :html _ :ctx _}
   (-> (weather-event {:memory {:city "Nowhere"} :state :loaded} "search")
       :ctx
       :error)
   := "No place called 'Nowhere' was found."
   (-> (weather-event {:memory {:city "Nowhere"} :state :loaded} "search")
       :html
       (testid-text "error"))
   := "No place called 'Nowhere' was found.")

 "6 weather: INIT from any state goes back to :loading without fetching (the skeleton's LOAD re-fetches on reconnect)"
 (with-redefs [weather-api/fetch-current-weather (spy/spy (fn [_] fake-weather))]
   (weather-event {:memory {:city "Barcelona"} :state :loaded} "init")
   := {:memory {:city "Barcelona"} :state :loading :html _ :ctx _}
   (weather-event {:memory {:city "Nowhere"} :state :failed} "init")
   := {:memory {:city "Nowhere"} :state :loading :html _ :ctx _}
   (spy/not-called? weather-api/fetch-current-weather) := true)

 "6 weather: WMO codes have a description, unknown ones fall back"
 (weather-api/describe-code 71) := "Light snow"
 (weather-api/describe-code 42) := "Weather code 42")

(tests
 ;; The countdown is a CONNECTED component, but nothing is kept between
 ;; events: a click starts from the tab's signals (it has none, so :idle), a
 ;; server event from the machine's initial state with its params. Every call
 ;; below starts its own flow, INITs it, sends the case's events through it
 ;; and stops it. START starts a real ticker, so a case that sends it stubs
 ;; the ticker with a spy (no real TICK arrives; the case sends its TICKs).
 (defn- through-a-flow
   "Starts a fresh countdown flow and INITs it, then sends each of `events` in
    order — `[:browser event opts]` is a POST to the connected path,
    `[:server event params]` is `ds/send-event!` (no request) — stops the flow
    and returns the last result."
   [& events]
   (let [[cf init] (ts/connect "live.countdown" "countdown1" {})]
     (try
       (reduce (fn [_ [kind event opts]]
                 (case kind
                   :browser (ts/flow-event cf "countdown1" event opts)
                   :server (ts/server-event cf event opts)))
               init
               events)
       (finally
         (ds/stop-component-flow! cf)))))

 "7 countdown: INIT through the flow — :idle at 5, :remaining is top-level ctx, never in :memory"
 (through-a-flow) := {:memory {} :state :idle :html _ :ctx _}
 (-> (through-a-flow)
     :ctx
     :remaining)
 := 5
 (-> (through-a-flow)
     :html
     (testid-text "remaining"))
 := "5"

 "7 countdown: START is a browser event through the flow; it moves to :running
  at 5 and starts the ticker for this tab"
 (with-redefs [countdown/start-ticker! (spy/spy)]
   (through-a-flow [:browser "start"]) := {:memory {} :state :running :html _ :ctx _}
   (spy/called-once-with? countdown/start-ticker! "countdown1" "tester" 5) := true)
 (with-redefs [countdown/start-ticker! (spy/spy)]
   (-> (through-a-flow [:browser "start"])
       :html
       (testid-text "remaining"))
   := "5")

 "7 countdown: a TICK is a server event — no request, no signals: it starts
  from the machine's initial state and shows the :remaining it carries"
 (through-a-flow [:server "tick" {:remaining 4}]) := {:memory {} :state :running :html _ :ctx _}
 (-> (through-a-flow [:server "tick" {:remaining 4}])
     :html
     (testid-text "remaining"))
 := "4"

 "7 countdown: the TICK carrying 0 lands in :done"
 (through-a-flow [:server "tick" {:remaining 0}]) := {:memory {} :state :done :html _ :ctx _}
 (-> (through-a-flow [:server "tick" {:remaining 0}])
     :html
     (testid-text "remaining"))
 := "0"
 (-> (through-a-flow [:server "tick" {:remaining 0}])
     :html
     (testid-text "phase")
     (str/includes? "server sent every tick"))
 := true

 "7 countdown: nothing is carried between events — after START (5), a TICK
  shows what it carries, not START's value minus one"
 (with-redefs [countdown/start-ticker! (spy/spy)]
   (-> (through-a-flow [:browser "start"]
                       [:server "tick" {:remaining 2}])
       :html
       (testid-text "remaining"))
   := "2")

 "7 countdown: the page sends no state, so \"Again\" on a finished countdown
  is a START from :idle — it restarts the ticker"
 (with-redefs [countdown/start-ticker! (spy/spy)]
   (through-a-flow [:server "tick" {:remaining 0}]
                   [:browser "start"])
   := {:memory {} :state :running :html _ :ctx _}
   (spy/called-once-with? countdown/start-ticker! "countdown1" "tester" 5) := true))
