# Components

## The contract

A component `<group>.<name>` is two namespaces under a search-path prefix:

| File | Must define | Role |
|------|-------------|------|
| `<group>/<name>.clj` | `(defn <name> [ctx] …)` | pure render: ctx → hiccup, or an effects list |
| `<group>/<name>_controller.clj` | `machine` | the state machine (see [State machines](state-machines.md)) |

The render fn is named after the **last namespace segment**. Anything else is a 400 on one-shot
events and a silent reconnect loop on connected ones ([Gotchas](gotchas.md)).

A different controller for the same render fn can be selected per request through the
four-segment route: `/ds/nds/<component>/<id>/<controller>/<event>`, where `<controller>` is the
controller namespace's last segment (for example `counter-page-controller`).

## Events

`POST /ds/nds/<component>/<component-id>/<event>`. The event name is upper-cased into the
machine's keyword: `increment` → `:INCREMENT`, `save_edit` → `:SAVE_EDIT`.

**Event params** are the request's query/form params plus every key of the JSON body that is not
this component's own slice (so other components' signals ride along). They reach the machine as
the second argument of `:actions` and `:cond` functions, with query-string values as **strings**.

```clojure
"data-on:click" "@post('/ds/nds/todomvc.todomvc/todos1/toggle?id=3')"
;; → (fn [ctx {:keys [id]}] (parse-long id))
```

## The context

The render fn and the machine's functions see one map:

| Key | What |
|-----|------|
| `:state` | the FSM state, a keyword or a vector of keywords for nested states |
| `:memory` | the component's own slice of the signal store, JSON-shaped |
| `:component-id` | the instance id — also the DOM id and the signal namespace |
| `:component-route` | the component name (`"counter.counter"`) for building URLs |
| `:logged-user` | whatever your middleware put under `[:params :logged-user]` |
| `:request-method` | `:get` / `:post`, for transitions that must not happen on a GET |
| `:app-config` | present only if the request carried one |

Only **`:memory` and `:state` survive between requests** — they are what the browser sends back.
Everything else is rebuilt each request from the request itself, and anything an action adds at
top level is gone on the next event. That is a feature: top-level ctx is the right home for
lazily loaded display data you do *not* want echoed back from the client.

## `:memory`, the signal store and `data-signals`

Put `(ds/ctx->data-signals ctx)` in the `data-signals` attribute of the component's root element.
It serializes `{<component-id> (assoc memory :state state)}` as JSON; Datastar keeps **one store
per page**, namespaced by component-id, and sends the whole store with every request.

Rules that follow from this:

- **JSON-shaped values only** in `:memory`: numbers, strings, booleans, nil, vectors, maps of those.
  A `java.util.Date` comes back as a string; a keyword as a string.
- **Bind inputs to scalars.** `data-bind="<cid>.<key>"` writes the field straight into `:memory`.
  Use camelCase keys for anything referenced in a Datastar expression (`$todos1.newTodo` is an
  identifier; `new-todo` is a subtraction).
- **Removed keys are sent back as `null`**: when an action `dissoc`s a memory key, the pipeline
  re-adds it as nil so the client deletes the signal. A test that compares `:memory` after a
  removal must expect the nil.
- Keys the server never seeded but a Datastar expression references are auto-created as `""`, which
  is truthy in Clojure. Seed every key you read.

## What a render fn may return

**Hiccup.** The whole component, root element carrying the component-id. One-shot responses
are `text/html`; Datastar morphs the response into the element with the same id. Over an SSE
stream the same hiccup is sent as a patch-elements event.

**An effects list** — a vector of vectors, when one morph is not enough:

```clojure
[[:patch [:div#form …] {}]                                 ; morph by id, or target with opts
 [:patch [:li …] {:d*/selector "#list" :d*/patch-mode :d*/pm-append}]
 [:patch-signals {:todos1 {:editingId nil}} {}]            ; change signals without touching the DOM
 [:execute-script "document.getElementById('x').focus()" {}]]
```

Option keys and values written as `:d*/…` keywords are resolved against
`starfederation.datastar.clojure.api` (`:d*/selector`, `:d*/patch-mode`, `:d*/pm-append`,
`:d*/pm-inner`, …). An effects list is answered as a one-shot SSE response with one event per
effect; a list with no patch at all is answered with an empty body.

`:broadcast` is **not** a render effect — the one-shot path ignores it. It belongs to
[`send-ui!`](connected-components.md).

## Full-page renders at a canonical URL

```clojure
(let [{:keys [html]} (ds/process-component-event req "home.home" "home" "INIT" nil)]
  (ds/html-response html))
```

Runs the pipeline in process (discovery → transition → render) so `GET /` can serve a page
component directly instead of redirecting into `/ds/…`. The examples app does this for its
shell pages.

## Discovery and the first request

Component namespaces are `require`d lazily, on the first request that needs them, **on the
request thread**. The cache holds namespace symbols, never vars, so REPL redefinitions are picked up.
`set-component-search-paths!` clears the cache; `warm-component-cache!` pre-loads a list of
components so the first click does not pay the load cost.
