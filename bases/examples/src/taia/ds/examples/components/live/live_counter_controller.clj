(ns taia.ds.examples.components.live.live-counter-controller
  "State machine for example 2. The count is a server-side atom; the
   INCREMENT/RESET actions change it and then push the fresh render to every
   connected tab with `send-ui!` and a `:broadcast` effect.

   Two things worth knowing:

   * `send-ui!` is addressed to (component-id, user-id). A `:patch` reaches
     that one user's stream; a `:broadcast` reaches every user connected under
     the component-id. The user-id must have an open stream (the caller does —
     it is looking at the page).
   * A click POSTs through the clicking tab's connected flow, and that POST
     ALSO answers with the render, so the clicking tab is patched twice with
     identical markup. Harmless, and it keeps the example to one render fn.
     In an application the push usually comes from somewhere else entirely
     (a background job, an agent, another user)."
  (:refer-clojure :exclude [reset!])
  (:require
   [taia.ds.datastar.interface :as ds]
   [taia.ds.examples.components.live.live-counter :as view]))

(defonce !count (atom 0))

(defn- with-count [ctx]
  (assoc ctx :count @!count))

(defn push-to-everyone!
  "Broadcasts the current render to every tab connected under this component-id."
  [ctx]
  (ds/send-ui! (:component-id ctx)
               (get-in ctx [:logged-user :user/id])
               [[:broadcast (view/live-counter ctx) {:d*/selector (str "#" (:component-id ctx))}]])
  ctx)

(defn- change! [f]
  (fn [ctx _]
    (swap! !count f)
    (push-to-everyone! (with-count ctx))))

(def machine
  {:id      :live-counter
   :initial :displayed
   :states  {:displayed {:on {:INIT      {:actions (fn [ctx _] (with-count ctx))}
                              :INCREMENT {:actions (change! inc)}
                              :RESET     {:actions (change! (constantly 0))}}}}})

(defn- send-through-any-tab!
  "Sends `event-name` through the flow of the first tab connected under
   `component-id` that takes it. Returns that tab's user-id, or nil when no
   tab is open."
  [component-id event-name]
  (some (fn [user-id]
          (when (ds/send-event! component-id user-id event-name {})
            user-id))
        (get (ds/connections) component-id)))

;; REPL helpers: as if someone had clicked. The event runs through one open
;; tab's flow; its action changes the count and broadcasts to every tab. No
;; user-id needed. Each returns the user-id it went through, or nil when no
;; tab is open.

(defn inc!
  "+1 for everyone (INCREMENT)."
  ([] (inc! "live1"))
  ([component-id] (send-through-any-tab! component-id "increment")))

(defn reset!
  "Back to 0 for everyone (RESET). Shadows `clojure.core/reset!` in this
   namespace only."
  ([] (reset! "live1"))
  ([component-id] (send-through-any-tab! component-id "reset")))

(comment
  ;; REPL, with the app running ((user/start!) or `bb dev`) and the page open:
  ;;   http://localhost:7788/ds/nds/home.home/home/init?example=live-counter
  ;; That tab is "demo-user" (no `&user=`); open a second one with
  ;; `&user=bob` to watch the fan-out.
  (ds/connections) ; => {"live1" ["demo-user" "bob"] …}

  ;; +1 / back to 0 for everyone, no user-id needed (nil when no tab is open)
  (inc!)
  (reset!)

  ;; An EVENT, as if demo-user had clicked: it runs through demo-user's flow,
  ;; so its render is pushed down demo-user's stream, and the INCREMENT action
  ;; broadcasts to everyone else with send-ui! as usual. False without a stream.
  (ds/send-event! "live1" "demo-user" "increment" {})
  (ds/send-event! "live1" "demo-user" "reset" {})

  ;; A PATCH, hand-built and broadcast: the send-ui! way, which bypasses the
  ;; component's render fn.
  (ds/send-ui! "live1" "demo-user"
               [[:broadcast [:div#live1 [:h2 "Pushed from the REPL"]] {:d*/selector "#live1"}]])

  ;; the server count, behind both
  @!count
  )
