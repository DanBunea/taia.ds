# Migrating from the in-app namespaces

taia.ds was extracted from an application where the same code lived under `ds.*`,
`state-machines.*`, `logging.*` and `extensions.*`. Polylith needs a top namespace, so everything
moved under `taia.ds.*`.

| Before | After |
|--------|-------|
| `ds.interface` | `taia.ds.datastar.interface` |
| `ds.adapters.handlers.context/ctx->data-signals` | `taia.ds.datastar.interface/ctx->data-signals` |
| `ds.adapters.handlers.response/->html` | `taia.ds.datastar.interface/html-response` |
| `ds.adapters.*`, `ds.domain.*` (internal) | `taia.ds.datastar.adapters.*`, `taia.ds.datastar.domain.*` |
| `state-machines.interface` | `taia.ds.state-machines.interface` |
| `state-machines.test-helpers` | `taia.ds.state-machines.test-helpers` |
| `logging.interface` | `taia.ds.logging.interface` |
| `extensions.interface` | `taia.ds.extensions.interface` |
| search path `"ds.adapters.components"` | `"taia.ds.datastar.adapters.components"` |
| vendored `/js/datastar.js`, `/js/sse-reconnect.js` | classpath `datastar/js/…`, served via `wrap-resource "datastar"` |

Behaviour is unchanged. Two additions: `ctx->data-signals` and `html-response` are on the
interface, so component code no longer needs the `adapters.handlers.*` namespaces.

Mechanical rewrite:

```shell
perl -pi -e 's/(?<![\w.\-])ds\.(interface|adapters|domain)\b/taia.ds.datastar.$1/g;
             s/(?<![\w.\-])state-machines\.(interface|test-helpers)\b/taia.ds.state-machines.$1/g;
             s/(?<![\w.\-])logging\.interface\b/taia.ds.logging.interface/g;
             s/(?<![\w.\-])extensions\.interface\b/taia.ds.extensions.interface/g' $(git ls-files '*.clj' '*.cljc')
```

Then update the search-path configuration and remove the vendored JS files.
