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
 * const handler: Program = (state, {event}, context) => {
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
 *   program: handler,
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

import type { Effect as GeneratedEffect } from '../generated/effects_pb.ts';
import type { Event } from '../generated/events_pb.ts';
import type { StepResponse } from '../generated/program_pb.ts';
import * as ProgramMessages from '../generated/program_pb.ts';
import { create, fromBinary, fromJson, toBinary, toJson } from '@bufbuild/protobuf';

export { create, fromBinary, fromJson, toBinary, toJson };

export type ProgramState = Uint8Array;

/** An effect represented by the generated protobuf oneof. */
export type Effect = GeneratedEffect;
export type EffectT = GeneratedEffect;

export interface StepContext {
  /**
   * The maximum number of effects the Runtime will accept in this step's output. Effects beyond this limit
   * are dropped.
   */
  effectLimit: number;
  /** The maximum byte length the Runtime will accept for the next execution state. */
  stateSizeLimit: bigint;
  /** The Mod's current number of available per-player burst chat tokens. */
  burstChatTokens: number;
  /** The Mod's current number of available per-player slow chat tokens. */
  slowChatTokens: number;
}

/**
 * A Program is an event handler that is called with the current value of a Cobbleputer state
 * and an Event and context, and returns the updated Cobbleputer state and a sequence of effects.
 */
export type Program = (state: ProgramState, event: Event, context: StepContext) => StepResponse;

/** Named initial state values available when a Cobbleputer loads this program. */
export type StartStates = Record<string, ProgramState>;

export interface StartOpts {
  program: Program;
  startStates: StartStates;
}

/**
 * Starts the Program API server for this Cobbleputer program.
 *
 * The server exposes the protocol endpoints required by the Cobbleware runtime:
 * `GET /startStates` and `POST /nextState`.
 */
export function start(opts: StartOpts): Deno.HttpServer {
  return Deno.serve(createProgramApiHandler(opts));
}

/**
 * Creates a handler for the Program API.
 *
 * Most programs should call {@link start} instead. This is exported for programs that need to
 * compose the Program API with another Deno HTTP handler.
 */
export function createProgramApiHandler(opts: StartOpts): (request: Request) => Promise<Response> {
  const startStatesResponse = encodeStartStates(opts.startStates);

  return async (request) => {
    const { pathname } = new URL(request.url);
    if (pathname === '/nextState') {
      return await handleNextState(request, opts.program);
    }
    if (pathname === '/startStates') {
      if (request.method !== 'GET') {
        return methodNotAllowed('GET');
      }

      return binaryResponse(startStatesResponse, 200);
    }

    return new Response(null, { status: 404 });
  };
}

async function handleNextState(request: Request, program: Program): Promise<Response> {
  if (request.method !== 'POST') {
    return methodNotAllowed('POST');
  }

  try {
    const stepRequest = fromBinary(
      ProgramMessages.StepRequestSchema,
      new Uint8Array(await request.arrayBuffer()),
    );
    if (stepRequest.event?.event.case === undefined) {
      throw new Error('StepRequest is missing an event');
    }

    return binaryResponse(
      toBinary(
        ProgramMessages.StepResponseSchema,
        program(stepRequest.state, stepRequest.event, {
          effectLimit: stepRequest.effectLimit,
          stateSizeLimit: stepRequest.stateSizeLimit,
          burstChatTokens: stepRequest.burstChatTokens,
          slowChatTokens: stepRequest.slowChatTokens,
        }),
      ),
      200,
    );
  } catch (error) {
    return binaryResponse(
      toBinary(
        ProgramMessages.ErrorResponseSchema,
        create(ProgramMessages.ErrorResponseSchema, { error: describeError(error) }),
      ),
      500,
    );
  }
}

function encodeStartStates(startStates: StartStates): Uint8Array {
  return toBinary(
    ProgramMessages.StartStatesResponseSchema,
    create(ProgramMessages.StartStatesResponseSchema, {
      startStates: Object.entries(startStates).map(([stateName, state]) =>
        create(ProgramMessages.StartStateSchema, { stateName, state })
      ),
    }),
  );
}

function binaryResponse(content: Uint8Array, status: number): Response {
  return new Response(new Blob([content.slice().buffer as ArrayBuffer]), {
    status,
    headers: { 'content-type': 'application/octet-stream' },
  });
}

function methodNotAllowed(allowedMethods: string): Response {
  return new Response(null, { status: 405, headers: { allow: allowedMethods } });
}

function describeError(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
