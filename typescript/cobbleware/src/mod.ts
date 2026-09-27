/**
 * Public entry point for Cobbleputer programs.
 */

import type { Effect as GeneratedEffect } from '../generated/effects_pb.ts';
import type { Event } from '../generated/events_pb.ts';
import type { StepResponse } from '../generated/program_pb.ts';
import * as ProgramMessages from '../generated/program_pb.ts';
import { create, fromBinary, fromJson, toBinary, toJson } from '@bufbuild/protobuf';

export * as Common from '../generated/common_pb.ts';
export * as Events from '../generated/events_pb.ts';
export * as Effects from '../generated/effects_pb.ts';
export * as Program from '../generated/program_pb.ts';
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
