(ns taia.ds.examples.examples-test
  "The examples, driven through the real pipeline (discovery → FSM → render)
   with no HTTP. Each block is also the executable spec the matching
   docs/examples article refers to."
  (:require
   [clojure.string :as str]
   [hyperfiddle.rcf :refer [tests]]
   [taia.ds.datastar.interface :as ds]
   [spy.core :as spy]
   [taia.ds.examples.components.live.live-counter-controller :as live]
   [taia.ds.examples.components.weather.weather-api :as weather-api]
   [taia.ds.examples.test-support :as ts :refer [run step testid-text]]))

;; Both prefixes: the examples first, the brick's own fixtures second, so the
;; datastar brick's discovery tests keep resolving "counter.counter".
(ds/set-component-search-paths! ["taia.ds.examples.components"
                                 "taia.ds.datastar.adapters.components"])

(tests
 "1 counter: INIT seeds 0, increments accumulate through the store, reset clears"
 (def c0 (run "basics.counter" "counter1" {} "init"))
 (:memory c0) := {:count 0}
 (:state c0) := :displayed
 (testid-text (:html c0) "count") := "0"

 (def c1 (step "basics.counter" "counter1" c0 "increment"))
 (def c2 (step "basics.counter" "counter1" c1 "increment"))
 (:count (:memory c2)) := 2
 (testid-text (:html c2) "count") := "2"
 (:count (:memory (step "basics.counter" "counter1" c2 "decrement"))) := 1
 (:count (:memory (step "basics.counter" "counter1" c2 "reset"))) := 0

 "1 counter: a count already in the store survives INIT (reconnect / second init)"
 (:count (:memory (run "basics.counter" "counter1" {:memory {:count 7}} "init"))) := 7

 "1 counter: every button posts to this component's own route"
 (set (ts/extract-post-urls (:html c0)))
 := #{"/ds/nds/basics.counter/counter1/decrement"
      "/ds/nds/basics.counter/counter1/increment"
      "/ds/nds/basics.counter/counter1/reset"})

(tests
 "2 live counter: the count is server-owned — it is in ctx, not :memory"
 (reset! live/!count 0)
 (def l0 (run "live.live-counter" "live1" {} "init"))
 (:memory l0) := {}
 (testid-text (:html l0) "count") := "0"
 (def l1 (step "live.live-counter" "live1" l0 "increment"))
 (testid-text (:html l1) "count") := "1"
 @live/!count := 1
 ;; a different user, a different tab: same number
 (testid-text (:html (run "live.live-counter" "live1" {:user "bob"} "init")) "count") := "1"
 (testid-text (:html (step "live.live-counter" "live1" l1 "reset")) "count") := "0"
 (reset! live/!count 0))

(tests
 "3 signup: invalid submit stays in :editing, records errors, returns an effects list that focuses the first invalid field"
 (def s0 (run "signup.signup" "signup1" {} "init"))
 (:memory s0) := {:name "" :email ""}
 (def s1 (step "signup.signup" "signup1" s0 "submit"))
 (:state s1) := :editing
 (get-in s1 [:memory :errors]) := {:name "Tell us your name" :email "That does not look like an email"}
 (mapv first (:html s1)) := [:patch :execute-script]
 (str/includes? (second (second (:html s1))) "signup1-name") := true
 (testid-text (first (rest (first (:html s1)))) "error-name") := "Tell us your name"

 "3 signup: a valid submit moves to :submitted and drops :errors (sent back as null, so the client deletes it)"
 (def s2 (run "signup.signup" "signup1" {:memory {:name "Ada" :email "ada@example.com" :errors {:name "x"}} :state :editing} "submit"))
 (:state s2) := :submitted
 (contains? (:memory s2) :errors) := true
 (get-in s2 [:memory :errors]) := nil
 (testid-text (:html s2) "welcome") := "Welcome, Ada!"

 "3 signup: INIT in :submitted is a no-op (the re-INIT hatch); RESET returns to a blank form"
 (:state (step "signup.signup" "signup1" s2 "init")) := :submitted
 (def s3 (step "signup.signup" "signup1" s2 "reset"))
 (:state s3) := :editing
 (select-keys (:memory s3) [:name :email]) := {:name "" :email ""})

(tests
 "4 wizard: hierarchical state, relative targets, guards"
 (def w0 (run "wizard.wizard" "wizard1" {} "init"))
 (:state w0) := [:editing :details]
 (def w1 (step "wizard.wizard" "wizard1" w0 "next"))
 (:state w1) := [:editing :details]
 (get-in w1 [:memory :error]) := "Name is required"
 (def w2 (run "wizard.wizard" "wizard1" {:memory {:name "Ada"} :state [:editing :details]} "next"))
 (:state w2) := [:editing :address]
 (contains? (:memory w2) :error) := false
 (:state (step "wizard.wizard" "wizard1" w2 "back")) := [:editing :details]
 (def w3 (run "wizard.wizard" "wizard1" {:memory {:name "Ada" :street "1 Loop" :city "Oxford"} :state [:editing :address]} "next"))
 (:state w3) := [:editing :review]

 "4 wizard: SUBMIT enters :saving, the invoke runs in the same request, on-done lands in :done with a receipt"
 (def w4 (step "wizard.wizard" "wizard1" w3 "submit"))
 (:state w4) := :done
 (str/starts-with? (get-in w4 [:memory :receipt]) "W-") := true
 (testid-text (:html w4) "receipt") := (str "Saved. Receipt " (get-in w4 [:memory :receipt]))

 "4 wizard: on-error returns to the review step with the message"
 (def w5 (run "wizard.wizard" "wizard1" {:memory {:name "fail" :street "s" :city "c"} :state [:editing :review]} "submit"))
 (:state w5) := [:editing :review]
 (get-in w5 [:memory :error]) := "The server refused to save (you typed 'fail')."

 "4 wizard: RESET is machine-wide"
 (def w6 (step "wizard.wizard" "wizard1" w4 "reset"))
 (:state w6) := [:editing :details]
 ;; :receipt was removed by RESET, so the pipeline sends it back as nil → the
 ;; client receives a JSON null and deletes the signal (assoc-removed-signal-nils).
 (:memory w6) := {:name "" :street "" :city "" :receipt nil})

(tests
 "5 todomvc: add reads the bound newTodo signal and clears it; ids come from nextId"
 (def t0 (run "todomvc.todomvc" "todos1" {} "init"))
 (:memory t0) := {:todos [] :nextId 1 :newTodo "" :filter "all" :editingId nil :editText ""}
 (def t1 (run "todomvc.todomvc" "todos1" {:memory (assoc (:memory t0) :newTodo "  buy milk ") :state :displayed} "add"))
 (get-in t1 [:memory :todos]) := [{:id 1 :text "buy milk" :completed false}]
 (get-in t1 [:memory :newTodo]) := ""
 (get-in t1 [:memory :nextId]) := 2
 (:todos (:memory (run "todomvc.todomvc" "todos1" {:memory (assoc (:memory t1) :newTodo "   ") :state :displayed} "add")))
 := (:todos (:memory t1))

 "5 todomvc: query-string params arrive as strings and are parsed"
 (def t2 (run "todomvc.todomvc" "todos1" {:memory (assoc (:memory t1) :newTodo "walk") :state :displayed} "add"))
 (def t3 (step "todomvc.todomvc" "todos1" t2 "toggle" {:id "1"}))
 (mapv :completed (get-in t3 [:memory :todos])) := [true false]
 (testid-text (:html t3) "remaining") := "1 item left"
 (def t4 (step "todomvc.todomvc" "todos1" t3 "filter" {:filter "active"}))
 (get-in t4 [:memory :filter]) := "active"
 (testid-text (:html t4) "todo-1") := nil
 (some? (testid-text (:html t4) "todo-2")) := true
 (def t4c (step "todomvc.todomvc" "todos1" t3 "filter" {:filter "completed"}))
 (get-in t4c [:memory :filter]) := "completed"
 (some? (testid-text (:html t4c) "todo-1")) := true
 (testid-text (:html t4c) "todo-2") := nil
 (get-in (step "todomvc.todomvc" "todos1" t4 "filter" {:filter "bogus"}) [:memory :filter]) := "all"
 (mapv :id (get-in (step "todomvc.todomvc" "todos1" t3 "destroy" {:id "2"}) [:memory :todos])) := [1]
 (mapv :completed (get-in (step "todomvc.todomvc" "todos1" t3 "toggle_all") [:memory :todos])) := [true true]
 (mapv :id (get-in (step "todomvc.todomvc" "todos1" t3 "clear_completed") [:memory :todos])) := [2]

 "5 todomvc: editing round-trips through editingId/editText; a blank save deletes"
 (def t5 (step "todomvc.todomvc" "todos1" t2 "edit" {:id "2"}))
 (select-keys (:memory t5) [:editingId :editText]) := {:editingId 2 :editText "walk"}
 (def t6 (run "todomvc.todomvc" "todos1" {:memory (assoc (:memory t5) :editText "walk the dog") :state :displayed} "save_edit"))
 (mapv :text (get-in t6 [:memory :todos])) := ["buy milk" "walk the dog"]
 (get-in t6 [:memory :editingId]) := nil
 (mapv :id (get-in (run "todomvc.todomvc" "todos1" {:memory (assoc (:memory t5) :editText " ") :state :displayed} "save_edit") [:memory :todos])) := [1]
 (get-in (step "todomvc.todomvc" "todos1" t5 "cancel_edit") [:memory :editingId]) := nil)

(def fake-weather
  {:place "Oslo, Norway" :temperature -1.4 :wind 11.0 :condition "Light snow" :observed-at "2026-11-09T15:00"})

(tests
 "6 weather: INIT renders the skeleton and does NOT fetch; the skeleton's data-init posts LOAD"
 (with-redefs [weather-api/fetch-current-weather (spy/spy (fn [_] fake-weather))]
   (def wx0 (run "weather.weather" "weather1" {} "init"))
   (:state wx0) := :loading
   (:memory wx0) := {:city "Barcelona"}
   (spy/not-called? weather-api/fetch-current-weather) := true
   (testid-text (:html wx0) "loading") := "…Loading the weather in Barcelona…"
   (ts/extract-post-urls (:html wx0)) := ["/ds/nds/weather.weather/weather1/load"])

 "6 weather: LOAD enters :fetching, the invoke runs in the same request, on-done lands in :loaded with the weather in ctx (not :memory)"
 (with-redefs [weather-api/fetch-current-weather (spy/spy (fn [_] fake-weather))]
   (def wx1 (step "weather.weather" "weather1" wx0 "load"))
   (spy/calls weather-api/fetch-current-weather) := [["Barcelona"]]
   (:state wx1) := :loaded
   (:weather (:ctx wx1)) := fake-weather
   (:memory wx1) := {:city "Barcelona"}
   (testid-text (:html wx1) "temperature") := "-1.4 °C"
   (testid-text (:html wx1) "place") := "Oslo, Norway")

 "6 weather: SEARCH captures ?city= into :memory before the invoke reads it; without the param the bound input wins"
 (with-redefs [weather-api/fetch-current-weather (spy/spy (fn [_] fake-weather))]
   (def wx2 (step "weather.weather" "weather1" wx1 "search" {:city "Oslo"}))
   (:state wx2) := :loaded
   (get-in wx2 [:memory :city]) := "Oslo"
   (spy/calls weather-api/fetch-current-weather) := [["Oslo"]]
   (def wx3 (run "weather.weather" "weather1" {:memory {:city "Tokyo"} :state :loaded} "search"))
   (spy/calls weather-api/fetch-current-weather) := [["Oslo"] ["Tokyo"]])

 "6 weather: a refused lookup lands in :failed with the message; the error is in ctx and the retry posts LOAD"
 (with-redefs [weather-api/fetch-current-weather (fn [city] (throw (ex-info "nope" {:message (str "No place called '" city "' was found.")})))]
   (def wx4 (run "weather.weather" "weather1" {:memory {:city "Nowhere"} :state :loaded} "search"))
   (:state wx4) := :failed
   (:error (:ctx wx4)) := "No place called 'Nowhere' was found."
   (contains? (:memory wx4) :error) := false
   (testid-text (:html wx4) "error") := "No place called 'Nowhere' was found.")

 "6 weather: INIT from any state goes back to :loading (reconnect re-fetches)"
 (:state (step "weather.weather" "weather1" wx1 "init")) := :loading
 (:state (step "weather.weather" "weather1" wx4 "init")) := :loading

 "6 weather: WMO codes have a description, unknown ones fall back"
 (weather-api/describe-code 71) := "Light snow"
 (weather-api/describe-code 42) := "Weather code 42")
