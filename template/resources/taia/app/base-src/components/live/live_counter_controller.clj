(ns {{top/ns}}.{{main/ns}}.web.components.live.live-counter-controller
  "State machine for the live counter. The count is the tab's: it lives in `:memory`
   and round-trips through the tab's signals. Nothing is kept on the server.

   * A click POSTs to the nds-connect path with the tab's signals, so
     INCREMENT starts from the count in its `:memory`.
   * A server event (`send-event!`, e.g. from the REPL) has no request and
     no signals, so it brings the count as its params, and INCREMENT starts
     from that. Its render is pushed down the tab's stream; the render's
     `data-signals` update the tab's store, so the next click carries it."
  (:refer-clojure :exclude [reset!])
  (:require
   [taia.ds.datastar.interface :as ds]))

(defn- seed [ctx _] (update-in ctx [:memory :count] #(or % 0)))

(defn- increment
  "+1 on the count the event brings: its params' (a server event), else its
   memory's (a click)."
  [ctx {:keys [count]}]
  (assoc-in ctx [:memory :count] (inc (or count (get-in ctx [:memory :count]) 0))))

(defn- reset [ctx _] (assoc-in ctx [:memory :count] 0))

(def machine
  {:id      :live-counter
   :initial :displayed
   :states  {:displayed {:on {:INIT      {:actions seed}
                              :INCREMENT {:actions increment}
                              :RESET     {:actions reset}}}}})

(defn- send-through-any-tab!
  "Sends `event-name` with `params` through the flow of the first tab
   connected under `component-id` that takes it. Returns that tab's user-id,
   or nil when no tab is open."
  [component-id event-name params]
  (some (fn [user-id]
          (when (ds/send-event! component-id user-id event-name params)
            user-id))
        (get (ds/connections) component-id)))

;; REPL helpers: server events into an open tab's flow. A server event has no
;; signals, so the REPL sends the memory itself, as params. No user-id needed.
;; Each returns the user-id it went through, or nil when no tab is open.

(defn inc!
  "+1 on `count` (INCREMENT with {:count count}): the tab then shows count + 1."
  ([count] (inc! "live1" count))
  ([component-id count] (send-through-any-tab! component-id "increment" {:count count})))

(defn reset!
  "Back to 0 (RESET). Shadows `clojure.core/reset!` in this namespace only."
  ([] (reset! "live1"))
  ([component-id] (send-through-any-tab! component-id "reset" {})))

(comment
  ;; REPL, with the app running ((user/start!) or `bb dev`) and the page open:
  ;;   http://localhost:{{port}}/ds/nds/home.home/home/init?example=live-counter
  ;; That tab is "demo-user" (no `&user=`).
  (ds/connections) ; => {"live1" ["demo-user"] …}

  ;; a server event has no signals: send the memory as params
  (ds/send-event! "live1" "demo-user" "increment" {:count 2}) ; the tab shows 3
  (ds/send-event! "live1" "demo-user" "reset" {})             ; back to 0

  ;; the same through the helpers, which pick an open tab for you
  (inc! 2)
  (reset!)
  )
