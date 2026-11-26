# Gotchas

Hard-won in production. Each entry names the symptom first.

## An unhandled event is dropped, not thrown

`transition` logs `Event X not found! … don't exist in machine: <id>` and returns the ctx
**unchanged**. No exception, no 500 — the component re-renders what it already had and looks like
it "does nothing". Connected components receive a second `:INIT` from the reconnect watchdog and
from users reopening a popup, so **every state that can be displayed needs an `:INIT` entry**, even
`{}`. When a component silently fails to refresh, grep the server log for `don't exist in machine`
before suspecting anything else.

## The render fn must be named after the last namespace segment

`chat.messages` → `my.app.components.chat.messages/messages`. Anything else is
`Component function not found`: a 400 on one-shot POSTs, but on `/ds/nds-connect/` the throw
happens inside the SSE open, the stream closes cleanly and the watchdog reconnects every ~2 s
forever with no server error logged. A tight reconnect loop means the pipeline threw inside open;
replay the same event as a POST to read the 400 body.

## `(:event ctx)` is always nil in production

The event lives on the pipeline map, not in the ctx the render fn receives. Match clauses gated on
it are dead code — and RCF tests that pass `:event` by hand pass anyway. Discriminate on ctx data.

## Only `:memory` and `:state` survive a request

Top-level ctx is rebuilt from the request every time. Display data stamped at INIT is gone on the
next event unless the invoke before *that* event stamps it again. Audit every reload path that
re-renders a header or a parent when you add always-visible data.

## Keep `:memory` JSON-shaped

A `java.util.Date` comes back as an ISO string, a keyword as a string. Store epoch millis and
strings; coerce at the one consuming boundary.

## A map in a bound signal aborts the whole attribute pass

`data-bind` assigns the signal to `input.value`; Datastar's signal objects have a null prototype,
so a map throws `Cannot convert object to primitive value` **inside the MutationObserver**, and
every `data-show` / `data-attr` after it in the patched subtree is never applied. The visible
symptom is far away — spinners that never hide, buttons stuck mid-click, a locked scroll — with
nothing wrong on the network tab. Bind scalars only; diagnose in the browser console.

The same abort happens for any invalid Datastar expression (a `?` in a signal name, for instance).
Sanitize derived signal names to `[A-Za-z0-9_]`.

## An unseeded signal is auto-created as `""`

Any `$cid.key` referenced in a Datastar expression that the server never seeded is created as the
empty string, sent back, and lands in `:memory` as `""` — **truthy** in Clojure. Seed every key you
read, or decide on the server.

## The signal store is page-global

One store per page, namespaced by component-id, and every `@post`/`@get` sends all of it. Your
component's slice becomes `:memory`; **everyone else's slices arrive as event params**. Signals
prefixed with `_` are Datastar-local and do not travel.

## A lazily loaded value must be a patch, not a signal

Several lazy `data-init` POSTs fire at once at cold start; each response echoes the store snapshot
its request was sent with, and the last one to land wins. A value the server computes late and
writes into `:memory` is overwritten by an earlier request's echo. Put it in top-level ctx and
`[:patch fragment {:d*/selector "#host"}]` a stable host element instead.

## Shrinking a keyed collection must tombstone

Re-seeding `lines` as `{"0" …}` after deleting `"1"` leaves the old row's bindings in the DOM until
the morph removes them; Datastar re-creates `"1"` as `{name: ""}` and the next POST saves a blank
row. Mark removed keys `{:deleted true}` and have the save strip them.

## `data-init` does not re-run on a morph with an unchanged value

RC8 re-applies an attribute plugin only when the attribute's **value** changed. If a `data-init`
does client-side post-processing (timezone formatting, say), make its value differ whenever the
element's content differs — an inert trailing comment with a hash of the rendered state. Prefer that
over a clock nonce so the render stays pure.

## `@post` from JS is not possible; a morph does not re-run `data-signals` on a kept element

File uploads cannot go through the SSE pipeline. Post a bare `fetch` + `FormData` to a plain Ring
route, then `.click()` a hidden *trampoline* element whose `data-on:click` expression resets the
signal and `@post`s the follow-up event. Check the target state actually handles that event.

## A morph drops the attributes the render does not produce

Datastar morphs an element by id and syncs its attributes to the new markup: anything the render
fn does not emit is removed, on the same DOM node. A `data-on:ds-reconnect` (or any marker) put
on a component's root from outside — the mount element sharing the component's id — is gone
after the first render, silently. Put such markers on a wrapper with another id.

## The first request for a component pays the namespace load, on the request thread

Measured at 3.7–6.1 s per component tree in a large app with RCF enabled. It looks intermittent
because it is once per tree per server start. `warm-component-cache!` at boot moves the cost out of
the first click.

## Invokes do not see event params

`:src` gets the ctx only. Capture the param in the transition's `:actions` first, then read it in
the invoke.

## A live-only detail vanishes on the next re-render

If a connected render shows something that only exists in the pushed event (a duration, a
transient flag) and the next request re-renders from persisted data, the detail disappears a
second later. Persist it, or make the re-render path produce it too.

## RC8 never reopens a cleanly closed stream

See [Connected components](connected-components.md#the-reconnect-watchdog). Also: a late close of
an *old* stream must not evict a reconnected one — the registry removal is identity-guarded for
that reason. Keep it that way if you touch it.

## A UI that never gets the end of a burst

If a connected component stops mid-update (a spinner that never ends, a reply that never
finishes) while the server finished long ago, look for `::user-write-queue-full` in the traces: a
user's write queue overflowed and the rest of the burst was dropped. Since 0.4.1 the queue holds
1024 writes and a full one closes the stream, so a client with `data-on:ds-reconnect` reconnects
and re-renders; one without that marker is left with a closed stream until a reload.

## Multipart, gzip and proxies

Jetty 12's GzipHandler excludes `text/event-stream` by default (not a suspect). Proxies may buffer
SSE; the connect handler sends `X-Accel-Buffering: no`, which nginx and Traefik honour.

## A server event starts from the machine's initial state

Nothing is kept between events, so a `send-event!` (no request, no signals) starts from the
machine's `:initial` state with an empty `:memory`. An event that can arrive in any state is
handled on the machine's own `:on`, and whatever its action needs travels as the event's params
(`{:remaining 4}`), not in a ctx left behind by an earlier event: there is none.

## Every one-shot event builds a flow

`/ds/nds/…` runs its event through a per-request `core.async.flow`, built and stopped per event.
Measured on the counter example: about 200 µs per event against 100 µs for the plain function
pipeline it replaced (p50 163 µs against 77 µs). There is no thread or heap growth, because the
proc loops run on virtual threads. Negligible next to a network round trip, but it is there.
`core.async.flow` is still alpha (`1.9.808-alpha1`).
