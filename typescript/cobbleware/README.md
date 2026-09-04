# Cobbleware TypeScript library

The Cobbleware library is the Edge Runtime-compatible API exposed to Cobbleputer program authors.

## Development

This package uses Deno:

```sh
deno task test
deno task check
deno task fmt
```

Protobuf bindings are generated from the repository-wide schemas in `protobuf/`.
Generation uses the installed `protoc` compiler and the pinned `@bufbuild/protoc-gen-es` plugin:

`protoc` and Node.js must be available on `PATH`; Node.js runs the local generator plugin.

```sh
deno task generate
```

`src/mod.ts` is the package entry point. It intentionally contains only portable
TypeScript and can be imported directly by an Edge Function.

`generated/internal_pb.ts` describes implementation details that are subject to breaking
changes without warning.
