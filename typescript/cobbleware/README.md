# Cobbleware TypeScript library

The Cobbleware library is the Edge Runtime-compatible API exposed to Cobbleputer program authors.

## Development

This package uses Deno:

```sh
deno task test
deno task check
deno task fmt
```

FlatBuffers bindings are generated from the repository-wide schema:

```sh
deno task generate
```

`src/mod.ts` is the package entry point. It intentionally contains only portable
TypeScript and can be imported directly by an Edge Function.

`generated/internal.ts` describes implementation details that are subject to breaking
changes without warning.