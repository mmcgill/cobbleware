import {
  create,
  Effects,
  fromBinary,
  type Program as EventProgram,
  toBinary,
  Program,
} from '@cobbleware/core';

export const handle: EventProgram = function handle(state, event) {
  let effects: Effects.Effect[] = [];
  if (event.event.case === 'puterReset') {
    effects = [create(Effects.EffectSchema, {
      effect: {
        case: 'emitBeep',
        value: create(Effects.EmitBeepSchema, {}),
      }
    })];
  } else if (event.event.case === 'puterPlaced') {
    effects = [create(Effects.EffectSchema, {
      effect: {
        case: 'sendChat',
        value: create(Effects.SendChatSchema, { msg: 'Hello world'}),
      }
    })];
  }
  return create(Program.StepResponseSchema, { state: state.slice(), effects });
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
    const stepRequest = fromBinary(Program.StepRequestSchema, new Uint8Array(content));
    if (stepRequest.event?.event.case === undefined) {
      throw new Error('StepRequest is missing an event');
    }

    const output = handle(stepRequest.state, stepRequest.event);
    return binaryResponse(encodeStepResponse(output), 200);
  } catch (error) {
    return binaryResponse(encodeErrorResponse(describeError(error)), 500);
  }
}

function createStartStatesResponse(): Uint8Array {
  return toBinary(
    Program.StartStatesResponseSchema,
    create(Program.StartStatesResponseSchema, {
      startStates: [
        create(Program.StartStateSchema, { stateName: 'one', state: new Uint8Array([1]) }),
        create(Program.StartStateSchema, { stateName: 'two', state: new Uint8Array([2]) }),
      ],
    }),
  );
}

function encodeStepResponse(response: Program.StepResponse): Uint8Array {
  return toBinary(Program.StepResponseSchema, response);
}

function encodeErrorResponse(error: string): Uint8Array {
  return toBinary(Program.ErrorResponseSchema, create(Program.ErrorResponseSchema, { error }));
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
