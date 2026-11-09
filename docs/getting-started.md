# Getting started

taia.ds renders UI on the server. A **component** is a pure render function plus a **state
machine**; the browser holds the component's state as [Datastar](https://data-star.dev/) signals,
sends them back with every event, and morphs the server's answer into the page. Long-lived
components keep an SSE stream open so the server can push.

## 1. Add the dependency

```clojure
io.github.danbunea/taia.ds {:mvn/version "0.1.0"}
```

The jar brings its own `datastar.js` (v1.0.0-RC.8) and `sse-reconnect.js` as classpath resources.

## 2. Mount the handlers

Your application owns the routes. There are two handlers and three route shapes; the path
parameters must be called `:component`, `:component-id`, `:event` (and optionally `:controller`).

```clojure
(require '[taia.ds.datastar.interface :as ds])

["/ds"
 ["/nds"
  ["/:component/:component-id/:controller/:event" {:handler ds/handler-component-event}]
  ["/:component/:component-id/:event"             {:handler ds/handler-component-event}]]
 ["/nds-connect"
  ["/:component/:component-id/:event"             {:handler ds/handler-connected-component-connect}]]]
```

Around them, the middleware the handlers rely on:

| Request key | Set by | Why |
|-------------|--------|-----|
| `:path-params` | your router | which component, instance and event |
| `:body-params` (JSON) on POST, `[:params :datastar]` on GET | muuntaja / `wrap-params` | the signal store round-trip |
| `[:params :logged-user :user/id]` | your auth middleware | SSE connections are keyed per user |
| `:app-config` (optional) | you | forwarded into every component ctx |

`handler-connected-component-connect` is **async only** (`[req respond raise]`), so run an async
Ring adapter (`ring.adapter.jetty` with `:async? true`). Serve the assets with
`(wrap-resource handler "datastar")` plus `wrap-content-type`, and load them in your page head:

```clojure
[:script {:type "module" :src "/js/datastar.js"}]
[:script {:src "/js/sse-reconnect.js" :defer true}]
```

The complete reference wiring is [`bases/examples/src/taia/ds/examples/core.clj`](../bases/examples/src/taia/ds/examples/core.clj):
about sixty lines of reitit, muuntaja and Jetty.

## 3. Tell discovery where your components live

```clojure
(ds/set-component-search-paths! ["my.app.components"])
```

A request for `counter.counter` then resolves `my.app.components.counter.counter` (the render fn,
named `counter`) and `my.app.components.counter.counter-controller` (a var called `machine`).
Prefixes are tried in order; the result is cached per component until the paths change.

Optionally, at startup, on a background thread:

```clojure
(ds/warm-component-cache! ["home.home" "chat.chat"])   ; pay the namespace-load cost at boot, not on the first click
(ds/set-observer! my-metrics-fn)                       ; event timings + SSE open/close as plain maps
```

## 4. Write a component

```
my/app/components/counter/counter.clj             ; (ns my.app.components.counter.counter)
my/app/components/counter/counter_controller.clj  ; (ns my.app.components.counter.counter-controller)
```

```clojure
(ns my.app.components.counter.counter
  (:require [taia.ds.datastar.interface :as ds]))

(defn counter [{:keys [component-id memory] :as ctx}]
  [:div {:id component-id :data-signals (ds/ctx->data-signals ctx)}
   [:p (str (:count memory 0))]
   [:button {"data-on:click" (str "@post('/ds/nds/counter.counter/" component-id "/increment')")} "+"]])
```

```clojure
(ns my.app.components.counter.counter-controller)

(def machine
  {:id :counter :initial :displayed
   :states {:displayed {:on {:INIT      {:actions (fn [ctx _] (update-in ctx [:memory :count] #(or % 0)))}
                             :INCREMENT {:actions (fn [ctx _] (update-in ctx [:memory :count] inc))}}}}})
```

## 5. Put it on a page

```clojure
[:div {:id "c1" "data-init" "@post('/ds/nds/counter.counter/c1/init')"} "loading…"]
```

Datastar fires INIT when the element appears; the response (the render) replaces the element with
the same id. Every click POSTs the signal store, the machine transitions, the render fn runs, the
element is morphed. Read [Components](components.md) for the full contract, then run the
[examples](examples/README.md).
