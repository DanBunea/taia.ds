(ns taia.ds.examples.examples-test
  "The examples, driven through the real pipeline (discovery → FSM → render)
   with no HTTP. Each block is also the executable spec the matching
   docs/examples article refers to.

   `run`/`step` return {:memory :state :html :ctx}. The assertions compare that
   whole map with RCF's `_` wildcard on the parts a step is not about — so an
   assertion reads as \"after this event the signal store is exactly this\",
   and a stray key fails it."
  (:require
   [clojure.string :as str]
   [hyperfiddle.rcf :refer [tests]]
   [taia.ds.datastar.interface :as ds]
   [spy.core :as spy]
   [taia.ds.examples.components.live.live-counter-controller :as live]
   [taia.ds.examples.components.weather.weather-api :as weather-api]
   [taia.ds.examples.test-support :as ts :refer [run step testid-text]]))

(tests
 "1 counter: INIT seeds 0, increments accumulate through the round-tripped store"
 (def c0 (run "basics.counter" "counter1" {} "init"))
 c0 := {:memory {:count 0} :state :displayed :html _ :ctx _}
 (testid-text (:html c0) "count") := "0"

 (def c1 (step "basics.counter" "counter1" c0 "increment"))
 (def c2 (step "basics.counter" "counter1" c1 "increment"))
 c2 := {:memory {:count 2} :state :displayed :html _ :ctx _}
 (testid-text (:html c2) "count") := "2"
 ;; the state viewer under every example prints exactly what round-trips
 (str/includes? (testid-text (:html c2) "inspect") "{:state :displayed, :memory {:count 2}}") := true
 (step "basics.counter" "counter1" c2 "decrement") := {:memory {:count 1} :state :displayed :html _ :ctx _}
 (step "basics.counter" "counter1" c2 "reset") := {:memory {:count 0} :state :displayed :html _ :ctx _}

 "1 counter: a count already in the store survives INIT (reconnect / second init)"
 (run "basics.counter" "counter1" {:memory {:count 7}} "init") := {:memory {:count 7} :state :displayed :html _ :ctx _}

 "1 counter: every button posts to this component's own route"
 (set (ts/extract-post-urls (:html c0)))
 := #{"/ds/nds/basics.counter/counter1/decrement"
      "/ds/nds/basics.counter/counter1/increment"
      "/ds/nds/basics.counter/counter1/reset"})

(tests
 "2 live counter: the count is server-owned — it is in ctx, not :memory, and every change is pushed to everyone connected"
 (reset! live/!count 0)
 (def l0 (run "live.live-counter" "live1" {} "init"))
 l0 := {:memory {} :state :displayed :html _ :ctx _}
 (testid-text (:html l0) "count") := "0"
 (with-redefs [ds/send-ui! (spy/spy)]
   (def l1 (step "live.live-counter" "live1" l0 "increment"))
   (spy/calls ds/send-ui!) := [["live1" "tester" [[:broadcast _ {:d*/selector "#live1"}]]]])
 l1 := {:memory {} :state :displayed :html _ :ctx _}
 (testid-text (:html l1) "count") := "1"
 ;; a different user, a different tab: same number
 (testid-text (:html (run "live.live-counter" "live1" {:user "bob"} "init")) "count") := "1"
 (with-redefs [ds/send-ui! (spy/spy)]
   (testid-text (:html (step "live.live-counter" "live1" l1 "reset")) "count") := "0"
   (spy/calls ds/send-ui!) := [["live1" "tester" [[:broadcast _ {:d*/selector "#live1"}]]]])
 (reset! live/!count 0))

(tests
 "3 signup: invalid submit stays in :editing, records errors, returns an effects list that focuses the first invalid field"
 (def s0 (run "signup.signup" "signup1" {} "init"))
 s0 := {:memory {:name "" :email ""} :state :editing :html _ :ctx _}
 (def s1 (step "signup.signup" "signup1" s0 "submit"))
 s1 := {:memory {:name "" :email "" :errors {:name "Tell us your name" :email "That does not look like an email"}}
        :state  :editing
        :html   [[:patch _] [:execute-script _]]
        :ctx    _}
 (str/includes? (second (second (:html s1))) "signup1-name") := true
 (testid-text (second (first (:html s1))) "error-name") := "Tell us your name"

 "3 signup: a valid submit moves to :submitted and drops :errors (sent back as null, so the client deletes it)"
 (def s2 (run "signup.signup" "signup1" {:memory {:name "Ada" :email "ada@example.com" :errors {:name "x"}} :state :editing} "submit"))
 s2 := {:memory {:name "Ada" :email "ada@example.com" :errors nil} :state :submitted :html _ :ctx _}
 (contains? (:memory s2) :errors) := true
 (testid-text (:html s2) "welcome") := "Welcome, Ada!"

 "3 signup: INIT in :submitted is a no-op (the re-INIT hatch); RESET returns to a blank form"
 (step "signup.signup" "signup1" s2 "init") := {:memory {:name "Ada" :email "ada@example.com" :errors nil} :state :submitted :html _ :ctx _}
 (step "signup.signup" "signup1" s2 "reset") := {:memory {:name "" :email "" :errors nil} :state :editing :html _ :ctx _})

(tests
 "4 wizard: hierarchical state, relative targets, guards"
 (def w0 (run "wizard.wizard" "wizard1" {} "init"))
 ;; INIT seeds nothing: the form renders missing fields as "" and data-bind fills them in
 w0 := {:memory {} :state [:editing :details] :html _ :ctx _}
 (step "wizard.wizard" "wizard1" w0 "next")
 := {:memory {:error "Name is required"} :state [:editing :details] :html _ :ctx _}
 (def w2 (run "wizard.wizard" "wizard1" {:memory {:name "Ada" :error "Name is required"} :state [:editing :details]} "next"))
 ;; :error was removed by the guard's action, so it is sent back as nil (a JSON null deletes the signal)
 w2 := {:memory {:name "Ada" :error nil} :state [:editing :address] :html _ :ctx _}
 (step "wizard.wizard" "wizard1" w2 "back") := {:memory {:name "Ada" :error nil} :state [:editing :details] :html _ :ctx _}
 (def w3 (run "wizard.wizard" "wizard1" {:memory {:name "Ada" :street "1 Loop" :city "Oxford"} :state [:editing :address]} "next"))
 w3 := {:memory {:name "Ada" :street "1 Loop" :city "Oxford"} :state [:editing :review] :html _ :ctx _}

 "4 wizard: SUBMIT enters :saving, the invoke runs in the same request, on-done lands in :done with a receipt"
 (def w4 (step "wizard.wizard" "wizard1" w3 "submit"))
 w4 := {:memory {:name "Ada" :street "1 Loop" :city "Oxford" :receipt _} :state :done :html _ :ctx _}
 (str/starts-with? (get-in w4 [:memory :receipt]) "W-") := true
 (testid-text (:html w4) "receipt") := (str "Saved. Receipt " (get-in w4 [:memory :receipt]))

 "4 wizard: on-error returns to the review step with the message"
 (run "wizard.wizard" "wizard1" {:memory {:name "fail" :street "s" :city "c"} :state [:editing :review]} "submit")
 := {:memory {:name "fail" :street "s" :city "c" :error "The server refused to save (you typed 'fail')."}
     :state  [:editing :review]
     :html   _
     :ctx    _}

 "4 wizard: RESET is machine-wide"
 ;; :receipt was removed by RESET, so the pipeline sends it back as nil → the
 ;; client receives a JSON null and deletes the signal (assoc-removed-signal-nils).
 (step "wizard.wizard" "wizard1" w4 "reset")
 := {:memory {:name "" :street "" :city "" :receipt nil} :state [:editing :details] :html _ :ctx _})

(def blank-todos {:todos [] :nextId 1 :newTodo "" :filter "all" :editingId nil :editText ""})

(tests
 "5 todomvc: add reads the bound newTodo signal and clears it; ids come from nextId"
 (def t0 (run "todomvc.todomvc" "todos1" {} "init"))
 t0 := {:memory blank-todos :state :displayed :html _ :ctx _}
 (def t1 (run "todomvc.todomvc" "todos1" {:memory (assoc (:memory t0) :newTodo "  buy milk ") :state :displayed} "add"))
 t1 := {:memory (assoc blank-todos :todos [{:id 1 :text "buy milk" :completed false}] :nextId 2) :state :displayed :html _ :ctx _}
 ;; a blank add is a no-op: nothing added, the input is left as typed
 (run "todomvc.todomvc" "todos1" {:memory (assoc (:memory t1) :newTodo "   ") :state :displayed} "add")
 := {:memory (assoc (:memory t1) :newTodo "   ") :state :displayed :html _ :ctx _}

 "5 todomvc: query-string params arrive as strings and are parsed"
 (def t2 (run "todomvc.todomvc" "todos1" {:memory (assoc (:memory t1) :newTodo "walk") :state :displayed} "add"))
 (def two-todos [{:id 1 :text "buy milk" :completed false} {:id 2 :text "walk" :completed false}])
 t2 := {:memory (assoc blank-todos :todos two-todos :nextId 3) :state :displayed :html _ :ctx _}
 (def t3 (step "todomvc.todomvc" "todos1" t2 "toggle" {:id "1"}))
 t3 := {:memory (assoc blank-todos :todos (assoc-in two-todos [0 :completed] true) :nextId 3) :state :displayed :html _ :ctx _}
 (testid-text (:html t3) "remaining") := "1 item left"
 (def t4 (step "todomvc.todomvc" "todos1" t3 "filter" {:filter "active"}))
 t4 := {:memory (assoc (:memory t3) :filter "active") :state :displayed :html _ :ctx _}
 (testid-text (:html t4) "todo-1") := nil
 (some? (testid-text (:html t4) "todo-2")) := true
 (def t4c (step "todomvc.todomvc" "todos1" t3 "filter" {:filter "completed"}))
 t4c := {:memory (assoc (:memory t3) :filter "completed") :state :displayed :html _ :ctx _}
 (some? (testid-text (:html t4c) "todo-1")) := true
 (testid-text (:html t4c) "todo-2") := nil
 (step "todomvc.todomvc" "todos1" t4 "filter" {:filter "bogus"}) := {:memory (:memory t3) :state :displayed :html _ :ctx _}
 (step "todomvc.todomvc" "todos1" t3 "destroy" {:id "2"})
 := {:memory (assoc (:memory t3) :todos [{:id 1 :text "buy milk" :completed true}]) :state :displayed :html _ :ctx _}
 (step "todomvc.todomvc" "todos1" t3 "toggle_all")
 := {:memory (assoc (:memory t3) :todos (mapv #(assoc % :completed true) two-todos)) :state :displayed :html _ :ctx _}
 (step "todomvc.todomvc" "todos1" t3 "clear_completed")
 := {:memory (assoc (:memory t3) :todos [{:id 2 :text "walk" :completed false}]) :state :displayed :html _ :ctx _}

 "5 todomvc: editing round-trips through editingId/editText; a blank save deletes"
 (def t5 (step "todomvc.todomvc" "todos1" t2 "edit" {:id "2"}))
 t5 := {:memory (assoc (:memory t2) :editingId 2 :editText "walk") :state :displayed :html _ :ctx _}
 (run "todomvc.todomvc" "todos1" {:memory (assoc (:memory t5) :editText "walk the dog") :state :displayed} "save_edit")
 := {:memory (assoc (:memory t2) :todos (assoc-in two-todos [1 :text] "walk the dog")) :state :displayed :html _ :ctx _}
 (run "todomvc.todomvc" "todos1" {:memory (assoc (:memory t5) :editText " ") :state :displayed} "save_edit")
 := {:memory (assoc (:memory t2) :todos [{:id 1 :text "buy milk" :completed false}]) :state :displayed :html _ :ctx _}
 (step "todomvc.todomvc" "todos1" t5 "cancel_edit") := {:memory (:memory t2) :state :displayed :html _ :ctx _})

(def fake-weather
  {:place "Oslo, Norway" :temperature -1.4 :wind 11.0 :condition "Light snow" :observed-at "2026-11-09T15:00"})

(tests
 "6 weather: INIT renders the skeleton and does NOT fetch; the skeleton's data-init posts LOAD"
 (with-redefs [weather-api/fetch-current-weather (spy/spy (fn [_] fake-weather))]
   (def wx0 (run "weather.weather" "weather1" {} "init"))
   wx0 := {:memory {:city "Barcelona"} :state :loading :html _ :ctx _}
   (spy/not-called? weather-api/fetch-current-weather) := true
   (testid-text (:html wx0) "loading") := "…Loading the weather in Barcelona…"
   (ts/extract-post-urls (:html wx0)) := ["/ds/nds/weather.weather/weather1/load"])

 "6 weather: LOAD enters :fetching, the invoke runs in the same request, on-done lands in :loaded with the weather in ctx (not :memory)"
 (with-redefs [weather-api/fetch-current-weather (spy/spy (fn [_] fake-weather))]
   (def wx1 (step "weather.weather" "weather1" wx0 "load"))
   (spy/calls weather-api/fetch-current-weather) := [["Barcelona"]]
   wx1 := {:memory {:city "Barcelona"} :state :loaded :html _ :ctx _}
   (:weather (:ctx wx1)) := fake-weather
   (testid-text (:html wx1) "temperature") := "-1.4 °C"
   (testid-text (:html wx1) "place") := "Oslo, Norway")

 "6 weather: SEARCH captures ?city= into :memory before the invoke reads it; without the param the bound input wins"
 (with-redefs [weather-api/fetch-current-weather (spy/spy (fn [_] fake-weather))]
   (step "weather.weather" "weather1" wx1 "search" {:city "Oslo"}) := {:memory {:city "Oslo"} :state :loaded :html _ :ctx _}
   (spy/calls weather-api/fetch-current-weather) := [["Oslo"]]
   (run "weather.weather" "weather1" {:memory {:city "Tokyo"} :state :loaded} "search") := {:memory {:city "Tokyo"} :state :loaded :html _ :ctx _}
   (spy/calls weather-api/fetch-current-weather) := [["Oslo"] ["Tokyo"]])

 "6 weather: a refused lookup lands in :failed with the message; the error is in ctx and the retry posts LOAD"
 (with-redefs [weather-api/fetch-current-weather (fn [city] (throw (ex-info "nope" {:message (str "No place called '" city "' was found.")})))]
   (def wx4 (run "weather.weather" "weather1" {:memory {:city "Nowhere"} :state :loaded} "search"))
   wx4 := {:memory {:city "Nowhere"} :state :failed :html _ :ctx _}
   (:error (:ctx wx4)) := "No place called 'Nowhere' was found."
   (testid-text (:html wx4) "error") := "No place called 'Nowhere' was found.")

 "6 weather: INIT from any state goes back to :loading (reconnect re-fetches)"
 (step "weather.weather" "weather1" wx1 "init") := {:memory {:city "Barcelona"} :state :loading :html _ :ctx _}
 (step "weather.weather" "weather1" wx4 "init") := {:memory {:city "Nowhere"} :state :loading :html _ :ctx _}

 "6 weather: WMO codes have a description, unknown ones fall back"
 (weather-api/describe-code 71) := "Light snow"
 (weather-api/describe-code 42) := "Weather code 42")
