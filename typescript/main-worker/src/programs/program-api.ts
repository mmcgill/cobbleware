import { fromBinary, toBinary } from '@cobbleware/core';
import * as Program from '@cobbleware/core/program';
import type { StartStates } from './types.ts';
import { isFieldSet } from '@bufbuild/protobuf';

export const PROGRAM_BINARY_CONTENT_TYPE = 'application/octet-stream';

export class StepResponseSizeLimitError extends Error {
  override name = 'StepResponseSizeLimitError';
}

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
  responseSizeLimit?: bigint,
): Promise<Program.StepResponse> {
  const body = toBinary(Program.StepRequestSchema, stepRequest);
  const response = await worker.fetch(
    new Request(NEXT_STATE_PATH, {
      method: 'POST',
      headers: { 'content-type': PROGRAM_BINARY_CONTENT_TYPE },
      body: new Blob([body.slice().buffer as ArrayBuffer]),
    }),
  );
  const boundedResponseBody = responseSizeLimit === undefined
    ? undefined
    : await readResponseBytes(response, responseSizeLimit);

  if (response.status !== 200) {
    const contentType = mediaType(response.headers.get('content-type'));
    let detail = '';
    if (contentType === PROGRAM_BINARY_CONTENT_TYPE) {
      try {
        const error = fromBinary(
          Program.ErrorResponseSchema,
          boundedResponseBody ?? await readResponseBytes(response),
        );
        if (error.error !== '') {
          detail = `: ${error.error}`;
        }
      } catch (error) {
        if (error instanceof StepResponseSizeLimitError) throw error;
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
  const step = fromBinary(
    Program.StepResponseSchema,
    boundedResponseBody ?? await readResponseBytes(response),
  );
  requireField(step, Program.StepResponseSchema.field.state, 'program response state');
  for (const effect of step.effects) {
    if (effect.effect.case === undefined) {
      throw new Error('program response effect is invalid');
    }
  }
  return step;
}

async function readResponseBytes(response: Response, limit?: bigint): Promise<Uint8Array> {
  if (response.body === null) return new Uint8Array();

  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let byteLength = 0;
  let countedBytes = 0n;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      countedBytes += BigInt(value.byteLength);
      if (limit !== undefined && countedBytes > limit) {
        await reader.cancel().catch(() => {});
        throw new StepResponseSizeLimitError(
          'program response body exceeds the configured size limit',
        );
      }
      chunks.push(value);
      byteLength += value.byteLength;
    }
  } finally {
    reader.releaseLock();
  }

  const bytes = new Uint8Array(byteLength);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return bytes;
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
