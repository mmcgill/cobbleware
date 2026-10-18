/**
 * # Cobbleware TypeScript library
 *
 * The Cobbleware library is the
 * [Edge Runtime](https://github.com/supabase/edge-runtime/)-compatible API
 * exposed to [Cobbleware](https://github.com/mmcgill/cobbleware) program authors.
 * This package is intended for use specifically with the Edge Runtime
 * (or Deno, for local testing). It is not compatible with Node.js.
 *
 * ## API modules
 *
 * - [`@cobbleware/core`](./) — program hosting and shared API helpers.
 * - [`@cobbleware/core/common`](./common/) — common protobuf message types.
 * - [`@cobbleware/core/effects`](./effects/) — effects a program can apply to the Minecraft world.
 * - [`@cobbleware/core/events`](./events/) — events delivered to a program.
 * - [`@cobbleware/core/program`](./program/) — protobuf message types used by the Program API.
 *
 * ## Writing a program
 *
 * Define a set of named initial states and a `Program` event handler, then call `start`.
 *
 * ```ts
 * import { create, type Program, start } from '@cobbleware/core';
 * import { EffectSchema, SendChatSchema } from '@cobbleware/core/effects';
 * import { StepResponseSchema } from '@cobbleware/core/program';
 *
 * // Define your program's initial states here. When you
 * // run your program on a Cobbleputer in Minecraft, you'll specify
 * // a start state to execute from.
 * const startStates = { initial: new Uint8Array() };
 *
 * // A Cobbleware Program is a state machine transition function.
 * // It gets called for every event dispatched to an executing
 * // Cobbleputer. A program step produces the next state along
 * // with a collection of Effects to be applied to the Minecraft world.
 * const program: Program = (state, {event}, context) => {
 *   let message: string | undefined;
 *   switch (event.case) {
 *     case 'started':
 *     case 'resumed':
 *       message = 'Hello, world!';
 *       break;
 *     case 'chatMessage':
 *       if (context.burstChatTokens === 0) {
 *         console.log('Chat echo rate limited: no burst chat tokens available.');
 *         break;
 *       }
 *       message = `Echo: ${event.value.message}`;
 *       break;
 *   }
 *
 *   return create(StepResponseSchema, {
 *     state,
 *     effects: message === undefined ? [] : [chat(message)],
 *   });
 * };
 *
 * function chat(message: string) {
 *   // Events and effects are represented with Protobufs.
 *   // The `create` function and other key '@bufbuild/protobuf' functions are re-exported for convenience.
 *   return create(EffectSchema, {
 *     effect: { case: 'sendChat', value: create(SendChatSchema, { msg: message }) },
 *   });
 * }
 *
 * // Start makes your start states available.
 * start({
 *   startStates,
 *   program,
 * });
 * ```
 *
 * The `start` function runs `Deno.serve` and serves the
 * [Program API](https://github.com/mmcgill/cobbleware/blob/main/docs/program-api.md) endpoints for you.
 * The Program API is the contract between the Mod and your program.
 *
 * See [examples](https://github.com/mmcgill/cobbleware/tree/main/typescript/examples)
 * for more involved examples.
 *
 * @module
 */

import { create, fromBinary, fromJson, toBinary, toJson } from '@bufbuild/protobuf';

export { create, fromBinary, fromJson, toBinary, toJson };

import type {
  Program,
  ProgramState,
  StartOpts,
  StartStates,
  StepContext,
  StepHandler,
} from './program_api.ts';

import { defaultStepHandler, start } from './program_api.ts';

export type { Program, ProgramState, StartOpts, StartStates, StepContext, StepHandler };

export { defaultStepHandler, start };
