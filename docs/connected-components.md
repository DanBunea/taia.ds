# Connected components and push

A one-shot component answers a request and is done. A **connected** component opens an SSE stream
the server can write to for as long as the page lives.

## Opening the stream

```clojure
[:div {:id "live1" "data-init" "@get('/ds/nds-connect/live.live-counter/live1/init')"} "…"]
```

`handler-connected-component-connect` registers the stream under `[component-id user-id]` (the
user from `[:params :logged-user :user/id]`), runs the INIT through the pipeline and sends the
render down the stream as a patch. The stream then stays open; the server heartbeats every
**15 s**. A second stream for the same `[component-id user-id]` replaces the first.

The handler is async only — `[req respond raise]`.

## Pushing

```clojure
(ds/send-ui! component-id user-id
             [[:patch [:div#live1 …] {:d*/selector "#live1"}]])              ; this user's stream

(ds/send-ui! component-id user-id
             [[:broadcast [:div#live1 …] {:d*/selector "#live1"}]])          ; every user under component-id
```

`send-ui!` is fire-and-forget: it queues on a channel, a consumer writes to each stream on that
user's own write queue, and a failed write evicts the connection. The calling user must have an
open stream (`:broadcast` is resolved from the registry, so it reaches everyone else too). The
caller's mulog context travels with the message, so pushes trace under the originating work.

Where pushes come from in practice: a background job, an LLM agent's output handler, another
user's request. The [live counter](examples/02-live-counter.md) pushes from a transition action to
keep the example in one file; do not read that as a recommendation.

## Mixing one-shot and connected

The connected stream is for the server → client direction. Clicks on a connected component are
ordinary `@post` events to `/ds/nds/…`; their one-shot response patches the clicking tab, and a
push patches everybody. Datastar morphs by id, so a tab receiving both ends up with the same DOM.

## The reconnect watchdog

Datastar RC8 does not reopen a stream that closes cleanly, retries an errored one ten times then
gives up, and Safari can kill a stream silently. `sse-reconnect.js` (shipped in the jar) watches
every `@get` to `/ds/nds-connect/` and, when it finishes, fails for good, is restored from BFCache
or sees 40 s of silence while the tab is visible, dispatches `ds-reconnect` on the element that
opened it. Opt in per element:

```clojure
[:div {:id "live1"
       "data-init"            "@get('/ds/nds-connect/live.live-counter/live1/init')"
       "data-on:ds-reconnect" "@get('/ds/nds-connect/live.live-counter/live1/init')"}]
```

A reconnect re-sends INIT **in whatever state the component is**, so every displayable state
must handle `:INIT` (an empty map is enough).

## Observing

```clojure
(ds/set-observer! (fn [m] …))   ; nil removes it
```

Both handlers report plain maps with bounded labels and no component-id:
`{:type :event :route :nds|:nds-connect :component … :event … :outcome … :seconds …}`,
`{:type :sse-opened :component …}`, `{:type :sse-closed :component … :seconds …}`. Observer
errors are swallowed; it must never fail a request. Plug Prometheus, mulog or a println in here.
