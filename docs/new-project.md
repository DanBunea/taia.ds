# Start a new project

taia.ds ships a [deps-new](https://github.com/seancorfield/deps-new) template, `taia/app`. One
command generates a Polylith workspace with everything in [Getting started](getting-started.md)
already wired:

- an async Jetty server
- reitit routes for the three handler shapes
- a dev namespace with an nREPL
- bb tasks
- tests
- an uberjar build

It comes with two components to start from: the **counter** and the **live counter**.

## Create it

Install deps-new once, as the `new` tool (Clojure CLI 1.12 or later):

```shell
clojure -Ttools install-latest :lib io.github.seancorfield/deps-new :as new
```

Then:

```shell
clojure -Tnew create :template io.github.danbunea/taia.ds%template%taia/app :name acme/shop
cd shop && bb dev
```

`bb dev` prints the app's URL (http://localhost:7788) and opens an nREPL on 7789.

The template lives in the [`template/`](../template) folder of the taia.ds repository. The
`%template%` in the name tells deps-new to look there. Some variants:

```shell
# pin a release (the tag pins the template and the taia.ds version it depends on)
clojure -Tnew create :template io.github.danbunea/taia.ds%template%taia/app#v0.3.2 :name acme/shop

# without installing the tool
clojure -Sdeps '{:deps {io.github.seancorfield/deps-new {:git/tag "v0.17.0" :git/sha "657fca6"}}}' \
  -X org.corfield.new/create :template io.github.danbunea/taia.ds%template%taia/app :name acme/shop
```

The template is not on Clojars. deps-new reads templates from git or a local folder only.

## Options

| Option | Default | |
|--------|---------|-|
| `:name` | (required) | `acme/shop` → namespaces under `acme.shop`, project `shop` |
| `:target-dir` | the name's last segment | where the project is written |
| `:taia-version` | the release the template was tagged with | e.g. `:taia-version '"0.3.1"'` |
| `:port` | `7788` | the HTTP port of `bb dev` and the jar's default |
| `:nrepl-port` | `7789` | the nREPL `bb dev` opens |

For example: `clojure -Tnew create :template … :name acme/shop :port 3000 :nrepl-port 3001`.

## What you get

For `:name acme/shop`:

```
shop/
  deps.edn  workspace.edn  bb.edn  tests.edn  README.md
  bases/web/src/acme/shop/web/
    core.clj                     routes, middleware, async Jetty, -main (PORT env var)
    inspect.clj                  the state + memory viewer under each component
    components/home/             the page itself (a component) + the registry of pages
    components/basics/           counter.clj + counter_controller.clj
    components/live/             live_counter.clj + live_counter_controller.clj
  bases/web/test/acme/shop/web/  test_support.clj + web_test.clj (RCF, no server)
  development/src/               dev.clj (start!/stop!/restart!, nREPL), user.clj
  projects/shop/                 deps.edn + build.clj: the deployable uberjar
  components/                    your Polylith components, as the app grows
```

```shell
bb dev        # the app + nREPL
bb test       # Kaocha + RCF inline tests
bb check      # poly check
bb uberjar    # projects/shop/target/shop.jar
bb run-server # java -jar it (PORT env var)
```

The code is the [examples](examples/README.md) app cut down to two components. The
[counter](examples/01-counter.md) and [live counter](examples/02-live-counter.md) walkthroughs
apply line for line. From the nREPL, with the live counter page open:

```clojure
(require '[acme.shop.web.components.live.live-counter-controller :as live])
(live/inc! 2)   ; the tab shows 3: a server event down the open stream
```

## Next steps

- Add a component: a render fn and a `_controller` machine under `components/<group>/`, plus an
  entry in the home controller's registry. See [Components](components.md) and
  [State machines](state-machines.md).
- Replace the `?user=` stand-in in `core.clj` (`wrap-logged-user`) with your authentication. SSE
  connections are keyed by `[:params :logged-user :user/id]`.
- Read the [Gotchas](gotchas.md) before you get far.

## Maintaining the template

In the taia.ds repository, `bb template-test` does the following:

1. Checks the template's version pin against the README.
2. Generates `acme/shop` into `target/template-test/` from the local `template/` folder.
3. Runs the generated project's `bb test`, `bb check` and `bb uberjar`.
4. Starts the jar and checks the pages over HTTP.

`bb template-test 0.3.2-SNAPSHOT` tests against another taia.ds version, for example one from
`bb install`.
