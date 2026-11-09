(ns taia.ds.examples.components.live.live-counter-controller
  "State machine for example 2. The count is a server-side atom; the
   INCREMENT/RESET actions change it and then push the fresh render to every
   connected tab with `send-ui!` and a `:broadcast` effect.

   Two things worth knowing:

   * `send-ui!` is addressed to (component-id, user-id). A `:patch` reaches
     that one user's stream; a `:broadcast` reaches every user connected under
     the component-id. The user-id must have an open stream (the caller does —
     it is looking at the page).
   * The one-shot POST ALSO answers with the render, so the clicking tab is
     patched twice with identical markup. Harmless, and it keeps the example
     to one render fn. In an application the push usually comes from
     somewhere else entirely (a background job, an agent, another user)."
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
