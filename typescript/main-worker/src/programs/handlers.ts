import { emptyResponse, isJsonRequest, jsonError, jsonResponse, readJson } from '../auth/http.ts';
import { fromBinary, fromJson, Program, toJson } from '@cobbleware/core';
import type { JsonValue } from '@bufbuild/protobuf';
import { requireSameOrigin } from '../auth/middleware.ts';
import { invokeNextState, invokeStartStates, PROGRAM_BINARY_CONTENT_TYPE } from './program-api.ts';
import {
  ProgramOperationConflictError,
  ProgramQuotaExceededError,
  type ProgramStore,
} from './types.ts';

const MULTIPART_CONTENT_TYPE = 'multipart/form-data';
const JSON_CONTENT_TYPE = 'application/json';
const PROGRAM_FIELD = 'program';
const NAME_FIELD = 'name';
const MAX_PROGRAM_NAME_LENGTH = 128;

export async function handleProgramCollection(
  request: Request,
  store: ProgramStore,
  playerUUID: string,
): Promise<Response> {
  if (request.method === 'GET') {
    try {
      return jsonResponse(await store.list(playerUUID));
    } catch (error) {
      return handleStoreError(error);
    }
  }
  if (request.method !== 'POST') {
    return methodNotAllowed('GET, POST');
  }

  const originError = requireSameOrigin(request);
  if (originError !== null) {
    return originError;
  }

  const contentType = request.headers.get('content-type')?.split(';', 1)[0].trim().toLowerCase();
  if (contentType !== MULTIPART_CONTENT_TYPE) {
    return jsonError('content type must be multipart/form-data', 415);
  }

  let form: FormData;
  try {
    form = await request.formData();
  } catch {
    return jsonError('invalid multipart request', 400);
  }

  const programParts = form.getAll(PROGRAM_FIELD);
  if (programParts.length !== 1 || !(programParts[0] instanceof File)) {
    return jsonError('multipart request must contain one program file', 400);
  }
  const file = programParts[0];

  const nameParts = form.getAll(NAME_FIELD);
  if (nameParts.length > 1 || (nameParts[0] !== undefined && typeof nameParts[0] !== 'string')) {
    return jsonError('program name must be a text field', 400);
  }

  const suppliedName = nameParts[0] as string | undefined;
  const name = normalizeProgramName(suppliedName ?? filenameName(file.name));
  if (name === null) {
    return jsonError('program name is invalid', 400);
  }

  const content = new Uint8Array(await file.arrayBuffer());
  try {
    const result = await store.upload(playerUUID, content, name);
    const response = jsonResponse(result.metadata, result.created ? 201 : 200);
    response.headers.set('location', programLocation(result.metadata.id));
    return response;
  } catch (error) {
    if (error instanceof ProgramQuotaExceededError) {
      return jsonError('player program quota exceeded', 413);
    }
    return handleStoreError(error);
  }
}

/** Returns the active program's metadata and start states for the Mod API. */
export async function handleProgram(
  request: Request,
  store: ProgramStore,
  playerMemoryQuotaBytes: number,
  playerUUID: string,
): Promise<Response> {
  if (request.method !== 'GET') {
    return methodNotAllowed('GET');
  }

  let program;
  try {
    program = await store.readActive(playerUUID);
  } catch (error) {
    if (error instanceof ProgramOperationConflictError) {
      return jsonError('another program operation is in progress', 409);
    }
    return jsonResponse({ programError: describeError(error) });
  }

  if (program === null) {
    return emptyResponse(404);
  }

  try {
    const worker = await store.run(playerUUID, { memoryQuotaBytes: playerMemoryQuotaBytes });
    return jsonResponse({
      ...program.metadata,
      startStates: await invokeStartStates(worker),
    });
  } catch (error) {
    return jsonResponse({ programError: describeError(error) });
  }
}

/** Returns the names of an authenticated Player's active program start states. */
export async function handlePlayerProgramStartStates(
  request: Request,
  store: ProgramStore,
  playerMemoryQuotaBytes: number,
  playerUUID: string,
  programId: string,
): Promise<Response> {
  if (request.method !== 'GET') {
    return methodNotAllowed('GET');
  }

  let program;
  try {
    program = await store.readActive(playerUUID);
  } catch (error) {
    return handleStoreError(error);
  }

  if (program === null || program.metadata.id !== programId) {
    return emptyResponse(404);
  }

  try {
    const worker = await store.run(playerUUID, { memoryQuotaBytes: playerMemoryQuotaBytes });
    const states = await invokeStartStates(worker);
    return jsonResponse({
      startStates: Object.keys(states),
    });
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error);
    return jsonError(`program could not provide start states: ${detail}`, 422);
  }
}

export async function handleProgramStep(
  request: Request,
  store: ProgramStore,
  playerMemoryQuotaBytes: number,
  playerUUID: string,
  programId: string,
): Promise<Response> {
  if (request.method !== 'POST') {
    return methodNotAllowed('POST');
  }

  const originError = requireSameOrigin(request);
  if (originError !== null) {
    return originError;
  }

  const contentType = mediaType(request.headers.get('content-type'));
  if (contentType !== PROGRAM_BINARY_CONTENT_TYPE && contentType !== JSON_CONTENT_TYPE) {
    return jsonError('content type must be application/octet-stream or application/json', 415);
  }

  let stepRequest: Program.StepRequest;
  try {
    if (isJsonRequest(request)) {
      stepRequest = fromJson(Program.StepRequestSchema, await readJson(request) as JsonValue);
    } else {
      stepRequest = fromBinary(
        Program.StepRequestSchema,
        new Uint8Array(await request.arrayBuffer()),
      );
    }
  } catch (error) {
    return jsonError(
      error instanceof Error ? error.message : 'request body could not be read',
      400,
    );
  }

  let program;
  try {
    program = await store.readActive(playerUUID);
  } catch (error) {
    return handleStoreError(error);
  }

  if (program === null || program.metadata.id !== programId) {
    return emptyResponse(404);
  }

  const worker = await store.run(playerUUID, { memoryQuotaBytes: playerMemoryQuotaBytes });
  const response = await invokeNextState(worker, stepRequest);

  try {
    return jsonResponse(
      toJson(Program.StepResponseSchema, response),
    );
  } catch {
    return jsonError('program returned an invalid StepResponse', 502);
  }
}

function describeError(error: unknown): string {
  if (error instanceof Error) {
    return error.stack ?? error.message;
  }
  return String(error);
}

function handleStoreError(error: unknown): Response {
  if (error instanceof ProgramOperationConflictError) {
    return jsonError('another program operation is in progress', 409);
  }
  throw error;
}

function filenameName(filename: string): string {
  return filename.replaceAll('\\', '/').split('/').pop() ?? '';
}

function normalizeProgramName(name: string): string | null {
  const trimmed = name.trim();
  if (
    trimmed.length === 0 ||
    Array.from(trimmed).length > MAX_PROGRAM_NAME_LENGTH ||
    /[\u0000-\u001f\u007f]/u.test(trimmed)
  ) {
    return null;
  }
  return trimmed;
}

function programLocation(id: string): string {
  return `/playerapi/v1/programs/${id}`;
}

function mediaType(value: string | null): string | undefined {
  return value?.split(';', 1)[0].trim().toLowerCase();
}

function methodNotAllowed(allowedMethods: string): Response {
  return new Response(null, {
    status: 405,
    headers: { allow: allowedMethods },
  });
}
