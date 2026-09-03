import * as flatbuffers from 'flatbuffers';
import {
  Effects,
  type EffectT,
  Events,
  Program,
  type Program as EventProgram,
} from '@cobbleware/core';

const helloWorldEffect: EffectT = new Effects.SendChatT('Hello world');

export const handle: EventProgram = function handle(state, _event) {
  const builder = new flatbuffers.Builder(128);
  builder.finish(
    new Program.StepResponseT([...state], [Effects.Effect.SendChat], [helloWorldEffect]).pack(
      builder,
    ),
  );
  return Program.StepResponse.getRootAsStepResponse(
    new flatbuffers.ByteBuffer(builder.asUint8Array()),
  );
};

const START_STATES_RESPONSE = createStartStatesResponse();

export async function handleProgramApi(request: Request): Promise<Response> {
  const { pathname } = new URL(request.url);
  if (pathname === '/nextState') {
    return handleNextState(request);
  }
  if (pathname !== '/startStates') {
    return new Response(null, { status: 404 });
  }
  if (request.method !== 'GET') {
    return new Response(null, { status: 405, headers: { allow: 'GET' } });
  }

  return new Response(new Blob([START_STATES_RESPONSE.slice().buffer as ArrayBuffer]), {
    status: 200,
    headers: { 'content-type': 'application/octet-stream' },
  });
}

async function handleNextState(request: Request): Promise<Response> {
  if (request.method !== 'POST') {
    return new Response(null, { status: 405, headers: { allow: 'POST' } });
  }

  const content = await request.arrayBuffer();
  try {
    const stepRequest = Program.StepRequest.getRootAsStepRequest(
      new flatbuffers.ByteBuffer(new Uint8Array(content)),
    ).unpack();
    if (stepRequest.event === null || stepRequest.eventType === Events.Event.NONE) {
      throw new Error('StepRequest is missing an event');
    }

    const output = handle(new Uint8Array(stepRequest.state), stepRequest.eventType);
    return binaryResponse(encodeStepResponse(output), 200);
  } catch (error) {
    return binaryResponse(encodeErrorResponse(describeError(error)), 500);
  }
}

function createStartStatesResponse(): Uint8Array {
  const builder = new flatbuffers.Builder(64);
  const root = new Program.StartStatesResponseT([
    new Program.StartStateT('one', [1]),
    new Program.StartStateT('two', [2]),
  ]).pack(builder);
  builder.finish(root);
  return builder.asUint8Array();
}

function encodeStepResponse(response: Program.StepResponse): Uint8Array {
  if (response.bb === null) {
    throw new Error('StepResponse is not initialized');
  }
  return response.bb.bytes();
}

function encodeErrorResponse(error: string): Uint8Array {
  const builder = new flatbuffers.Builder(128);
  builder.finish(new Program.ErrorResponseT(error).pack(builder));
  return builder.asUint8Array();
}

function binaryResponse(content: Uint8Array, status: number): Response {
  return new Response(new Blob([content.slice().buffer as ArrayBuffer]), {
    status,
    headers: { 'content-type': 'application/octet-stream' },
  });
}

function describeError(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

if (import.meta.main) {
  Deno.serve(handleProgramApi);
}
