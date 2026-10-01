import { fromBinary, Program, toBinary } from '@cobbleware/core';
import type { StartStates } from './types.ts';
import { isFieldSet } from '@bufbuild/protobuf';

export const PROGRAM_BINARY_CONTENT_TYPE = 'application/octet-stream';

const START_STATES_PATH = 'http://cobbleware.local/startStates';
const NEXT_STATE_PATH = 'http://cobbleware.local/nextState';

export async function invokeStartStates(worker: CobblewareUserWorker): Promise<StartStates> {
  const response = await worker.fetch(new Request(START_STATES_PATH, { method: 'GET' }));
  if (response.status !== 200) {
    throw new Error(`program returned HTTP ${response.status} for /startStates`);
  }

  if (mediaType(response.headers.get('content-type')) !== PROGRAM_BINARY_CONTENT_TYPE) {
    throw new Error('program returned an unexpected /startStates content type');
  }

  try {
    const root = fromBinary(
      Program.StartStatesResponseSchema,
      new Uint8Array(await response.arrayBuffer()),
    );
    const startStates: StartStates = {};
    for (const startState of root.startStates) {
      const { stateName, state } = startState;
      if (Object.hasOwn(startStates, stateName)) {
        throw new Error(`start state name is repeated: ${stateName}`);
      }
      startStates[stateName] = [...state];
    }
    return startStates;
  } catch (error) {
    if (error instanceof Error && error.message.startsWith('start state')) {
      throw error;
    }
    throw new Error(`program returned an invalid /startStates response: ${describeError(error)}`);
  }
}

export async function invokeNextState(
  worker: CobblewareUserWorker,
  stepRequest: Program.StepRequest,
): Promise<Program.StepResponse> {
  const body = toBinary(Program.StepRequestSchema, stepRequest);
  const response = await worker.fetch(
    new Request(NEXT_STATE_PATH, {
      method: 'POST',
      headers: { 'content-type': PROGRAM_BINARY_CONTENT_TYPE },
      body: new Blob([body.slice().buffer as ArrayBuffer]),
    }),
  );

  if (response.status !== 200) {
    const contentType = mediaType(response.headers.get('content-type'));
    let detail = '';
    if (contentType === PROGRAM_BINARY_CONTENT_TYPE) {
      try {
        const error = fromBinary(
          Program.ErrorResponseSchema,
          new Uint8Array(await response.arrayBuffer()),
        );
        if (error.error !== '') {
          detail = `: ${error.error}`;
        }
      } catch {
        detail = ' (response body was not a valid ErrorResponse)';
      }
    }
    throw new Error(
      `program returned HTTP ${response.status} with content type ${
        contentType ?? 'absent'
      }${detail}`,
    );
  }
  if (mediaType(response.headers.get('content-type')) !== PROGRAM_BINARY_CONTENT_TYPE) {
    throw new Error('program returned an unexpected /nextState content type');
  }
  const step = fromBinary(Program.StepResponseSchema, new Uint8Array(await response.arrayBuffer()));
  requireField(step, Program.StepResponseSchema.field.state, 'program response state');
  for (const effect of step.effects) {
    if (effect.effect.case === undefined) {
      throw new Error('program response effect is invalid');
    }
  }
  return step;
}

function requireField(
  message: object,
  field: Parameters<typeof isFieldSet>[1],
  name: string,
): void {
  if (!isFieldSet(message as never, field)) {
    throw new Error(`${name} is required`);
  }
}

function mediaType(value: string | null): string | undefined {
  return value?.split(';', 1)[0].trim().toLowerCase();
}

function describeError(error: unknown): string {
  if (error instanceof Error) {
    return error.stack ?? error.message;
  }
  return String(error);
}
