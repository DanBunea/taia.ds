# CLAUDE.md — taia.ds

Polylith library: server-side UI components over Datastar SSE. Published as `io.github.danbunea/taia.ds`.

## Commands

```shell
bb test          # unit tests: Kaocha + RCF inline tests (clojure -M:dev:test)
bb e2e-test      # Playwright suite vs the examples app on :7799 (scripts/e2e.clj; Node >= 18 via nvm); bb e2e-test <spec> [--headed]
bb check         # poly check (workspace validation)
bb info          # poly info
bb dev           # examples app on http://localhost:7788, nREPL on 7789
bb api-index     # regenerate docs/api.md (run after changing an interface)
bb format        # cljfmt fix   (bb format:check to verify)
bb jar           # build target/taia.ds-<version>.jar from projects/taia-ds
bb install       # jar + install into ~/.m2
bb deploy        # jar + deploy to Clojars (CLOJARS_USERNAME / CLOJARS_PASSWORD)
```

## Layout

- `components/<brick>/` — `deps.edn` (paths only) + `src/taia/ds/<brick>/...`; public API in `taia.ds.<brick>.interface`. Libraries are declared in `projects/taia-ds/deps.edn` and the root `:dev` alias, never in bricks.
- `projects/taia-ds/` — the published library (all four bricks). `workspace.edn` marks `datastar` as `:necessary` there (it is the top of the dependency tree). `projects/examples/` — bricks + the `examples` base; runnable, never published.
- `bases/examples/` — `core.clj` (the reference Ring/reitit/Jetty wiring: async Jetty, both handler arities, `wrap-resource "datastar"` + `wrap-content-type`, a `?user=` stand-in for auth), `components/home/` (the page is itself a component: a GET on `/ds/nds/home.home/home/init?example=…` renders it — there is no page route; `start!` returns that URL), `components/<group>/<name>.clj` + `<name>_controller.clj` (six examples, one concept each; the weather one calls Open-Meteo unless `TAIA_EXAMPLES_FAKE_WEATHER` is set), `test/` (`test_support.clj` drives the pipeline in process; `examples_test.clj` is the executable spec every `docs/examples/*.md` article points at). A new example = component pair + registry entry + article + test block, and it ends its root element with `(inspect/state-pre ctx)` — the state + memory viewer every example shows.
- `development/src/dev.clj` — starts the examples app and an nREPL on 7789 (Clojure MCP `clojure_eval` with `port 7789` reaches the running server, e.g. `send-ui!`).
- `e2e/` — Playwright specs, one per example, all under `tests/helpers/console-guard.ts` (fails on any console error: the only detector of a Datastar attribute-pass abort). `live-counter.spec.ts` uses two browser contexts for the SSE fan-out. A new example gets a spec here too.
- `docs/img/counter-flow.svg` — the README animation (SMIL, 13 s loop, timeline in the file's header comment); edit the SVG, then `bb flow-gif` renders `counter-flow.gif` frame by frame through `e2e/render-flow.mjs` (steps the SVG clock, so the GIF is exact). Keep the code it shows in sync with `basics/counter*.clj`.
- `docs/` — cljdoc articles (`doc/cljdoc.edn` is the tree). `docs/gotchas.md` is the distilled production knowledge: add to it when a bug teaches something about the framework. `docs/api.md` is generated.
- `build.clj` — tools.build; inlines bricks, writes a Maven-only pom, refuses to build if a `poly/*` dep leaks into the pom.

## Conventions

- Keep the bricks application-agnostic: no product CSS, routes, or component names in `components/`. Bases and consumers use `taia.ds.<brick>.interface` only (`poly check` enforces it) — if component code needs a helper from `adapters.*`, expose it on the interface.
- Tests are inline RCF `(tests …)` blocks in `src` (no `deftest`); src dirs are Kaocha test paths. Values bound with `def`, assertions top-level.
- Dynamic requires: component discovery resolves `<search-path>.<component>` and `-controller` siblings at runtime; the default search path is EMPTY — the library ships no components; `discovery_test.clj` defines its fixture in memory under its own prefix.
- `datastar.js` and `dev.data-star.clojure/sdk` versions must match (both RC8).
- Brick-local notes: `components/datastar/CLAUDE.md`.
