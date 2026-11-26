# Connected components and push

A one-shot component answers a request and is done. A **connected** component opens an SSE stream
the server can write to for as long as the page lives.

## Opening the stream

```clojure
[:div {:id "live1-mount" "data-init" "@post('/ds/nds-connect/live.live-counter/live1/init')"}
 [:div {:id "live1"} "…"]]
```

The nds-connect path has **one job: opening a connection** that the server can later reach with
`send-event!` (and `send-ui!`). Mount it once, on any method:

```clojure
["/nds-connect"
 ["/:component/:component-id/:event" {:handler ds/handler-connected-component-connect}]]
```

**Every request on it opens a connection**, whatever its method (`@get` or `@post`; `@post` sends
the signals as a JSON body instead of a query parameter) and whatever its event (`init` is a
convention, not a rule). `handler-connected-component-connect` answers with an SSE stream, starts
a new **flow** for the component (below), registers the stream and the flow under
`[component-id user-id]` (the user from `[:params :logged-user :user/id]`), runs the request's event
through the flow and sends the render down the stream as a patch. The stream then stays open; the
server heartbeats every **15 s**. A new connection for the same `[component-id user-id]` replaces
the old one and stops the old one's flow, so the last connection opened is the one `send-event!`
reaches.

The handler is async only — `[req respond raise]`.

## The component flow

Every event — one-shot or connected — runs through a `clojure.core.async.flow` of two procs:

```
in-chan ──▶ :controller ──:to-view──▶ :view ──▶ out-chan
            (state machine)            (render)
```

The machine and the render fn are resolved once, when the flow is built. A one-shot event
(`/ds/nds/…`) gets a flow of its own, which is stopped as soon as it answers. A connection's flow
lives as long as its stream: it runs the event that opened the connection, then every
`send-event!` addressed to it, one after the other.

**Nothing is kept between events**, one-shot or connected. A browser event starts from the
tab's signals (its `:state` and `:memory`). A server event has no request and no signals: it
starts from the machine's initial state, and brings what it needs as its params. Whatever an
action adds to the ctx for a render (a result list, say) is rendered, sent, and gone; nothing
grows on the server per connection. That is why browser events don't need the connection's flow.

## Clicks

Clicks on a connected component are ordinary one-shot events on `/ds/nds/…`:

```clojure
[:button {"data-on:click" "@post('/ds/nds/live.countdown/countdown1/start')"} "Start"]
```

Each runs in a flow of its own, from the tab's signals, and is answered with the render (HTML, or
a one-shot SSE for an effects list). Don't send them to `/ds/nds-connect/…`: a request there opens
a connection of its own, which then stays open on the clicked element and replaces the page's
connection as the one `send-event!` reaches.

## Server events

```clojure
(ds/send-event! "countdown1" "alice" "tick" {:remaining 4})   ; => true, or false with no open stream
```

![The live counter: a click is a one-shot event in a flow of its own; a REPL send-event! goes through the connection's flow; both run INCREMENT, the click from the tab's signals, the REPL with the memory as params; the count goes 1, 2, 3, the last render over the open stream](img/repl-event-flow.gif)

It goes through the flow of that user's connection, after any event already in it, from the
machine's initial state with its params; its render is pushed down that user's stream, on the same ordered write queue as `send-ui!`. The sender's mulog context travels
with it. This is the counterpart of `send-ui!` that keeps the component's own render fn as the
single source of markup: send the *event*, not the patch.

### What a server event starts from

Since a server event starts from the machine's initial state, handle it where that state can see
it (on the machine's own `:on`, for an event that can arrive in any state) and send what its
action needs as params. The countdown's ticker sends `{:remaining 4}`, `{:remaining 3}`, … and
its TICK, handled at the machine level, sets `:remaining` from them. State that must be shared
or outlive a tab lives in your own storage, and actions read it. The live counter's count is the
tab's, in `:memory`: a server event sends it as params, `{:count 2}`.

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
user's request. Prefer `send-event!` when the push is a render of the component itself: it
keeps the component's own render fn as the single source of markup.

## The reconnect watchdog

Datastar RC8 does not reopen a stream that closes cleanly, retries an errored one ten times then
gives up, and Safari can kill a stream silently. `sse-reconnect.js` (shipped in the jar) watches
every element marked `data-on:ds-reconnect`, whatever method its fetch uses, and when that fetch
finishes, fails for good, is restored from BFCache or sees 40 s of silence while the tab is
visible, dispatches `ds-reconnect` on it. Opt in on the element that opens the connection:

```clojure
[:div {:id                     "live1-mount"
       "data-init"            "@post('/ds/nds-connect/live.live-counter/live1/init')"
       "data-on:ds-reconnect" "@post('/ds/nds-connect/live.live-counter/live1/init')"}
 [:div {:id "live1"} "…"]]
```

**Put the marker on a wrapper, never on the component's own root.** The component renders
`#live1`, and the morph drops every attribute the render does not produce: a
`data-on:ds-reconnect` on `#live1` is gone after the first render, and the watchdog never fires.

A reconnect re-sends its request (INIT here) **in whatever state the component is**, so every
displayable state must handle that event (an empty map is enough).

What a reconnect means on the server:

- **A new connection is a new flow.** The reconnect registers the new stream and its flow under
  `[component-id user-id]`, replacing the old entry and stopping the old flow. The old stream's
  late close cannot evict the new one. Nothing is lost: the flow kept nothing.
- **Server events in the gap are dropped, not queued.** With no entry, `send-event!` answers
  false; with an entry whose stream is dead but not yet noticed, it answers true and the render
  goes to the dead stream. Send each server event with everything it needs as params (the countdown's
  `{:remaining n}`), so the next one sets the page right; the reconnect's request re-renders from the
  tab's own signals.
- **A client too slow for its write queue gets its stream closed.** A user's writes wait in one
  queue of `user-write-buffer` (1024) writes. A healthy client drains a burst of pushes in
  milliseconds, so only a dead or stalled one fills it; then the write is dropped, the entry
  removed and the stream closed, because a stream that lost a write is out of step with the
  server. Its fetch ends, and the `data-on:ds-reconnect` marker turns that into a reconnect whose
  request re-renders from the server's state. Without the marker nothing reopens it: RC8 does not
  reopen a clean close.
- **One stream per `[component-id user-id]`, not per tab.** Two tabs of the same user on the
  same component-id share the slot: the last connection opened wins, and pushes go to that tab
  only.

## Observing

```clojure
(ds/set-observer! (fn [m] …))   ; nil removes it
```

Both handlers report plain maps with bounded labels and no component-id:
`{:type :event :route :nds|:nds-connect :component … :event … :outcome … :seconds …}`
(`:nds-connect` is the render of the event that opened a connection),
`{:type :sse-opened :component …}`, `{:type :sse-closed :component … :seconds …}`. Observer
errors are swallowed; it must never fail a request. Plug Prometheus, mulog or a println in here.
