import {
  StartStateSchema,
  StartStatesResponseSchema,
  type StepRequest,
  StepRequestSchema,
  type StepResponse,
  StepResponseSchema,
} from '@cobbleware/core/program';
import { fromBinary, toBinary } from '@cobbleware/core';
import { ErrorResponseSchema } from '@cobbleware/core/internal';
import { create } from '@bufbuild/protobuf';
import type { Event } from '@cobbleware/core/events';

export type ProgramState = Uint8Array;

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

type Handler = (request: Request) => Promise<Response>;
type Middleware = (next: Handler) => Handler;
export type StepHandler = (request: StepRequest) => Promise<Response>;

function applyMiddlewares<T extends Handler>(base: T, middlewares: Array<(next: T) => T>): T {
  let result = base;
  for (const mw of middlewares) {
    result = mw(result);
  }
  return result;
}

function notFoundHandler(_request: Request): Promise<Response> {
  return Promise.resolve(new Response(null, { status: 404 }));
}

function startStateMiddleware(startStates: StartStates): Middleware {
  const startStatesResponseBody = encodeStartStates(startStates);
  return (next) => {
    return (request) => {
      const { pathname } = new URL(request.url);
      if (pathname === '/startStates') {
        return Promise.resolve(
          request.method === 'GET'
            ? binaryResponse(startStatesResponseBody, 200)
            : methodNotAllowed('GET'),
        );
      }
      return next(request);
    };
  };
}

function stepMiddleware(stepHandler: StepHandler): Middleware {
  return (next) => {
    return async (request) => {
      const { pathname } = new URL(request.url);
      if (pathname === '/nextState') {
        if (request.method !== 'POST') {
          return Promise.resolve(methodNotAllowed('POST'));
        }

        const stepRequest = fromBinary(
          StepRequestSchema,
          new Uint8Array(await request.arrayBuffer()),
        );
        return stepHandler(stepRequest);
      }
      return next(request);
    };
  };
}

function errorHandlingMiddleware(handler: Handler): Handler {
  return async (next) => {
    try {
      return await handler(next);
    } catch (error) {
      console.error(error, 'Uncaught exception from program');
      return Promise.resolve(binaryResponse(
        toBinary(
          ErrorResponseSchema,
          create(ErrorResponseSchema, { error: describeError(error) }),
        ),
        500,
      ));
    }
  };
}

/** Named initial state values available when a Cobbleputer loads this program. */
export type StartStates = Record<string, ProgramState>;

type StartProgram = {
  program: Program;
  stepHandler?: never;
};

type StartStepHandler = {
  program?: never;
  stepHandler: StepHandler;
};

export type StartOpts = {
  startStates: StartStates;
} & (StartProgram | StartStepHandler);

/**
 * Returns a StepHandler for the given program that handles response serialization.
 *
 * @param program a program handler function
 * @returns a StepHandler for the program
 */
export function defaultStepHandler(program: Program): StepHandler {
  return (request) => {
    if (request.event?.event.case === undefined) {
      throw new Error('StepRequest is missing an event');
    }
    return Promise.resolve(binaryResponse(
      toBinary(
        StepResponseSchema,
        program(request.state, request.event, {
          effectLimit: request.effectLimit,
          stateSizeLimit: request.stateSizeLimit,
          burstChatTokens: request.burstChatTokens,
          slowChatTokens: request.slowChatTokens,
        }),
      ),
      200,
    ));
  };
}

export function createProgramApiHandler(opts: StartOpts): Handler {
  const { program, stepHandler, startStates } = opts;
  return applyMiddlewares(
    notFoundHandler,
    [
      startStateMiddleware(startStates),
      stepMiddleware(stepHandler || defaultStepHandler(program)),
      errorHandlingMiddleware,
    ],
  );
}

/**
 * Starts the Program API server for this Cobbleputer program.
 *
 * The server exposes the protocol endpoints required by the Cobbleware runtime
 * and handles serialization/deserialization and basic error handling.
 *
 * For normal usage, supply your StartStates and Program function.
 *
 * For advanced use cases where you want full control over the HTTP-level Response,
 * you can supply a StepHandler function instead of a Program.
 */
export function start(opts: StartOpts): Deno.HttpServer {
  return Deno.serve(createProgramApiHandler(opts));
}

function encodeStartStates(startStates: StartStates): Uint8Array {
  return toBinary(
    StartStatesResponseSchema,
    create(StartStatesResponseSchema, {
      startStates: Object.entries(startStates).map(([stateName, state]) =>
        create(StartStateSchema, { stateName, state })
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
