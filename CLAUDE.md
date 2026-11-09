# CLAUDE.md — taia.ds

Polylith library: server-side UI components over Datastar SSE. Published as `io.gitlab.danbunea/taia.ds`.

## Commands

```shell
bb test          # unit tests: Kaocha + RCF inline tests (clojure -M:dev:test)
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
- `bases/examples/` — `core.clj` (the reference Ring/reitit/Jetty wiring: async Jetty, both handler arities, `wrap-resource "datastar"` + `wrap-content-type`, a `?user=` stand-in for auth), `layout.clj` (registry + page shell), `components/<group>/<name>.clj` + `<name>_controller.clj` (five examples, one concept each), `test/` (`test_support.clj` drives the pipeline in process; `examples_test.clj` is the executable spec every `docs/examples/*.md` article points at). A new example = component pair + registry entry + article + test block.
- `development/src/dev.clj` — starts the examples app and an nREPL on 7789 (Clojure MCP `clojure_eval` with `port 7789` reaches the running server, e.g. `send-ui!`).
- `docs/` — cljdoc articles (`doc/cljdoc.edn` is the tree). `docs/gotchas.md` is the distilled production knowledge: add to it when a bug teaches something about the framework. `docs/api.md` is generated.
- `build.clj` — tools.build; inlines bricks, writes a Maven-only pom, refuses to build if a `poly/*` dep leaks into the pom.

## Conventions

- Keep the bricks application-agnostic: no product CSS, routes, or component names in `components/`. Bases and consumers use `taia.ds.<brick>.interface` only (`poly check` enforces it) — if component code needs a helper from `adapters.*`, expose it on the interface.
- Tests are inline RCF `(tests …)` blocks in `src` (no `deftest`); src dirs are Kaocha test paths. Values bound with `def`, assertions top-level.
- Dynamic requires: component discovery resolves `<search-path>.<component>` and `-controller` siblings at runtime; the default search path is `taia.ds.datastar.adapters.components`. Any rename of the example components must update `discovery.clj` tests.
- `datastar.js` and `dev.data-star.clojure/sdk` versions must match (both RC8).
- Brick-local notes: `components/datastar/CLAUDE.md`.
