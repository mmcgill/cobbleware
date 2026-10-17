# Cobbleware TypeScript library

The Cobbleware library is the
[Edge Runtime](https://github.com/supabase/edge-runtime/)-compatible API
exposed to [Cobbleware](https://github.com/mmcgill/cobbleware) program authors.
This package is intended for use specifically with the Edge Runtime
(or Deno, for local testing). It is not compatible with Node.js.

## Writing a program

Define a set of named initial states and an event handler, then call `start`. The library runs
`Deno.serve` and implements the Program API endpoints for you.

```ts
import { create, type Program as EventHandler, start } from '@cobbleware/core';
import { StepResponseSchema } from '@cobbleware/core/program';

const handler: EventHandler = (state, _event) => create(StepResponseSchema, { state, effects: [] });

start({
  startStates: { initial: new Uint8Array() },
  program: handler,
});
```

See [examples](https://github.com/mmcgill/cobbleware/tree/main/typescript/examples)
for example usage.

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

The generated protobuf modules are available through the `common`, `effects`, `events`, and
`program` subpaths. The `internal` subpath describes implementation details that are subject to
breaking changes without warning.

## Versioning and Releases

Pre-release and release versions are staged for publication to NPM. Pre-release
versions are written in [deno.json](deno.json) as `X.Y.Z-alpha`, and published with
an appended build number and the `alpha` [dist tag](https://docs.npmjs.com/adding-dist-tags-to-packages).

The [build-lib.yml](../../.github/workflows/build-lib.yml) workflow, manually run from `main`,
stages the package using npm trusted publishing. A maintainer must approve the
staged package in npm before it is publicly installable.
