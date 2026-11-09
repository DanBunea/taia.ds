# CLAUDE.md — taia.ds

Polylith library: server-side UI components over Datastar SSE. Published as `io.gitlab.danbunea/taia.ds`.

## Commands

```shell
bb test          # unit tests: Kaocha + RCF inline tests (clojure -M:dev:test)
bb check         # poly check (workspace validation)
bb info          # poly info
bb dev           # example server on http://localhost:7788 (/ and /connected), nREPL on 7789
bb format        # cljfmt fix   (bb format:check to verify)
bb jar           # build target/taia.ds-<version>.jar from projects/taia-ds
bb install       # jar + install into ~/.m2
bb deploy        # jar + deploy to Clojars (CLOJARS_USERNAME / CLOJARS_PASSWORD)
```

## Layout

- `components/<brick>/` — `deps.edn` (paths only) + `src/taia/ds/<brick>/...`; public API in `taia.ds.<brick>.interface`. Libraries are declared in `projects/taia-ds/deps.edn` and the root `:dev` alias, never in bricks.
- `projects/taia-ds/` — the published library (all four bricks). `workspace.edn` marks `datastar` as `:necessary` there (it is the top of the dependency tree).
- `development/src/dev.clj` — Ring/reitit/Jetty wiring of the handlers + the counter example pages, plus an nREPL on 7789 (use the Clojure MCP `clojure_eval` with `port 7789` to poke the running server, e.g. `send-ui!`). It is the reference for how a consumer's base mounts the library: async Jetty, both handler arities, `wrap-resource "datastar"` + `wrap-content-type`.
- `build.clj` — tools.build; inlines bricks, writes a Maven-only pom, refuses to build if a `poly/*` dep leaks into the pom.

## Conventions

- Keep the bricks application-agnostic: no product CSS, routes, or component names in `components/`.
- Tests are inline RCF `(tests …)` blocks in `src` (no `deftest`); src dirs are Kaocha test paths. Values bound with `def`, assertions top-level.
- Dynamic requires: component discovery resolves `<search-path>.<component>` and `-controller` siblings at runtime; the default search path is `taia.ds.datastar.adapters.components`. Any rename of the example components must update `discovery.clj` tests.
- `datastar.js` and `dev.data-star.clojure/sdk` versions must match (both RC8).
- Brick-local notes: `components/datastar/CLAUDE.md`.
