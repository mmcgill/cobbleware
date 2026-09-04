# Cobbleware Hello World

A minimal Cobbleputer program for the custom Edge Runtime Main worker.

From this directory:

```sh
deno task test
deno task check
deno task bundle
deno task serve
deno task integrate
```

The `bundle` task tree-shakes the program with Deno's esbuild-powered bundler
before writing `hello-world.eszip`; it intentionally does not minify the
resulting JavaScript. Set `EDGE_RUNTIME_BIN` to use a different Edge Runtime
binary. The serve task packages the program and serves the archive at
`http://127.0.0.1:8000/hello-world.eszip`.

The `integrate` task is not included in `deno task test`. It bundles the current
program, then exercises a Main Worker at `http://localhost:9000` using the Mod API
key `secret`.
