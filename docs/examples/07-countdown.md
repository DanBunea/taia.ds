# 7 · Countdown

`live.countdown` — [`countdown.clj`](../../bases/examples/src/taia/ds/examples/components/live/countdown.clj),
[`countdown_controller.clj`](../../bases/examples/src/taia/ds/examples/components/live/countdown_controller.clj).

A connected component whose state lives in its **flow** (see
[Connected components](../connected-components.md#the-component-flow)). The home page mounts it with
`@get('/ds/nds-connect/…/init')`, which opens the stream and starts the component's flow. Nothing
is kept between events: every TICK carries the value to show.

**State machine**

```mermaid
stateDiagram-v2
  direction LR
  [*] --> idle : INIT / remaining = 5
  idle --> running : START (browser) / remaining = 5, start the ticker
  idle --> running : TICK {remaining n} (server, any state) / remaining = n
  idle --> done : TICK {remaining 0} (server, any state) / remaining = 0
```

**What to look at**

- No `data-signals` and no atom: `:remaining` comes with each TICK, as its params.
- START is a `@post` to the **nds-connect** path, not `/ds/nds/`. It runs through the tab's flow
  and the POST is answered with the `:running` render directly.
- The START action starts a ticker that counts down itself and calls
  `ds/send-event! cid user "tick" {:remaining n}` once a second, n = 4 … 0.
- Each TICK is a server event: no request, no signals, nothing kept from the event before. It
  starts from the machine's initial state, so TICK is handled on the machine's own `:on` (from
  any state), and its action, the named fn `tick`, sets `:remaining` from the params. The render
  fn turns it into a patch pushed down the stream. The controller never builds a patch by hand,
  unlike the [live counter](02-live-counter.md)'s `send-ui!`.
- `send-event!` returns false once the tab's stream has closed, and that stops the ticker.
- Each tab (each `?user=`) has its own stream, so its own flow and its own countdown.

**Try it**: open `?example=countdown` and press Start. To drive it from a REPL while the page
is open (without `&user=…` the examples app signs you in as `demo-user`, as the page footer says):

```clojure
(taia.ds.datastar.interface/connections)                                       ; => {"countdown1" ["demo-user"]}
(taia.ds.datastar.interface/send-event! "countdown1" "demo-user" "tick" {:remaining 3})   ; show 3
(taia.ds.datastar.interface/send-event! "countdown1" "demo-user" "start" {})              ; START from the server
```

`send-event!` answers `false` when that user has no open stream under that component-id;
`connections` shows who does. The [live counter](02-live-counter.md) shows a click and a REPL
event going through the same in-chan, step by step.

**Caveat**: the page sends no state, so a reload (or "Again" on a finished countdown) starts from
`:idle`; a ticker still running keeps sending its ticks, and they show up wherever they land.

**Spec**: block 7 in `examples_test.clj` (a connected flow driven headless with `ts/connect`,
`ts/flow-event` and `ts/server-event`) and `e2e/tests/countdown.spec.ts`.
