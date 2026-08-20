# Cobbleware Hello World

A minimal Cobbleputer program for the custom Edge Runtime Main worker.

From this directory:

```sh
deno task test
deno task check
deno task package
deno task serve
```

The package task writes `hello-world.eszip`. Set `EDGE_RUNTIME_BIN` to use a
different Edge Runtime binary. The serve task packages the program and serves
the archive at `http://127.0.0.1:8000/hello-world.eszip`.
