import { emptyResponse, jsonError, jsonResponse } from '../auth/http.ts';
import { requireSameOrigin } from '../auth/middleware.ts';
import { PROGRAM_BINARY_CONTENT_TYPE, programErrorResponse, type ProgramRunner } from './start-states.ts';
import {
  ProgramOperationConflictError,
  ProgramQuotaExceededError,
  type ProgramStore,
} from './types.ts';

const MULTIPART_CONTENT_TYPE = 'multipart/form-data';
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

export async function handleStartStates(
  request: Request,
  store: ProgramStore,
  runner: ProgramRunner,
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
    return programErrorResponse(error);
  }

  if (program === null) {
    return emptyResponse(404);
  }

  try {
    return jsonResponse({ startStates: await runner.startStates(request, playerUUID, program) });
  } catch (error) {
    return programErrorResponse(error);
  }
}

export async function handleProgramStep(
  request: Request,
  store: ProgramStore,
  runner: ProgramRunner,
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

  const contentType = request.headers.get('content-type')?.split(';', 1)[0].trim().toLowerCase();
  if (contentType !== PROGRAM_BINARY_CONTENT_TYPE) {
    return jsonError('content type must be application/octet-stream', 415);
  }

  let body: Uint8Array;
  try {
    // Player-supplied protobuf messages are opaque at this boundary. Do not unpack or verify them.
    body = new Uint8Array(await request.arrayBuffer());
  } catch (error) {
    return jsonError(error instanceof Error ? error.message : 'request body could not be read', 400);
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

  return await runner.step(request, playerUUID, program, body);
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

function methodNotAllowed(allowedMethods: string): Response {
  return new Response(null, {
    status: 405,
    headers: { allow: allowedMethods },
  });
}
