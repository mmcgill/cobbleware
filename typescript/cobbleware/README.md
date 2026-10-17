# Cobbleware TypeScript library

The Cobbleware library is the
[Edge Runtime](https://github.com/supabase/edge-runtime/)-compatible API
exposed to [Cobbleware](https://github.com/mmcgill/cobbleware) program authors.
This package is intended for use specifically with the Edge Runtime
(or Deno, for local testing). It is not compatible with Node.js.

## Writing a program

Define a set of named initial states and a `Program` event handler, then call `start`.

```ts
import { create, type Program, start } from '@cobbleware/core';
import { EffectSchema, SendChatSchema } from '@cobbleware/core/effects';
import { StepResponseSchema } from '@cobbleware/core/program';

// Define your program's initial states here. When you
// run your program on a Cobbleputer in Minecraft, you'll specify
// a start state to execute from.
const startStates = { initial: new Uint8Array() };

// A Cobbleware Program is a state machine transition function.
// It gets called for every event dispatched to an executing
// Cobbleputer. A program step produces the next state along
// with a collection of Effects to be applied to the Minecraft world.
const handler: Program = (state, { event }, context) => {
  let message: string | undefined;
  switch (event.case) {
    case 'started':
    case 'resumed':
      message = 'Hello, world!';
      break;
    case 'chatMessage':
      if (context.burstChatTokens === 0) {
        console.log('Chat echo rate limited: no burst chat tokens available.');
        break;
      }
      message = `Echo: ${event.value.message}`;
      break;
  }

  return create(StepResponseSchema, {
    state,
    effects: message === undefined ? [] : [chat(message)],
  });
};

function chat(message: string) {
  // Events and effects are represented with Protobufs.
  // The `create` function and other key '@bufbuild/protobuf' functions are re-exported for convenience.
  return create(EffectSchema, {
    effect: { case: 'sendChat', value: create(SendChatSchema, { msg: message }) },
  });
}

// Start makes your start states available
start({
  startStates,
  program: handler,
});
```

The `start` function runs `Deno.serve` and serves the
[Program API](https://github.com/mmcgill/cobbleware/blob/main/docs/program-api.md) endpoints for you.
The Program API is the contract between the Mod and your program.

See [examples](https://github.com/mmcgill/cobbleware/tree/main/typescript/examples)
for more involved examples.

## Development

This package uses Deno:

```sh
deno task test
deno task check
deno task fmt
deno task doc
```

`deno task doc` generates a searchable API reference in `reference-docs/` for the public package
entry points. The directory is ignored by Git.

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
