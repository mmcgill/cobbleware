# Cobbleware TypeScript library

The Cobbleware library is the Edge Runtime-compatible API exposed to Cobbleputer program authors.

## Writing a program

Define a set of named initial states and an event handler, then call `start`. The library runs
`Deno.serve` and implements the Program API endpoints for you.

```ts
import { create, Program, type Program as EventHandler, start } from '@cobbleware/core';

const handler: EventHandler = (state, _event) =>
  create(Program.StepResponseSchema, { state, effects: [] });

start({
  startStates: { initial: new Uint8Array() },
  program: handler,
});
```

See [`../hello-world/src/index.ts`](../hello-world/src/index.ts) for a complete program. When
testing an entry point that calls `start`, wrap that call in `if (import.meta.main)`.

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
