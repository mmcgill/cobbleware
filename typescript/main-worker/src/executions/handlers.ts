import { create, fromBinary, toBinary } from '@cobbleware/core';
import * as Internal from '@cobbleware/core/internal';
import type { AppConfig } from '../config.ts';
import { hasBearerToken } from '../auth/http.ts';
import { type ExecutionManager, ExecutionRequestError } from './manager.ts';

export const EXECUTIONS_CONTENT_TYPE = 'application/octet-stream';

export async function handleExecutions(
  request: Request,
  config: AppConfig,
  manager: ExecutionManager,
): Promise<Response> {
  if (request.method !== 'POST') {
    return executionError('method must be POST', 405, { allow: 'POST' });
  }
  if (config.apiKey === undefined || config.apiKey === '') {
    return executionError('server authentication is not configured', 500);
  }
  if (!hasBearerToken(request, config.apiKey)) {
    return executionError('unauthorized', 401, { 'www-authenticate': 'Bearer' });
  }
  if (mediaType(request.headers.get('content-type')) !== EXECUTIONS_CONTENT_TYPE) {
    return executionError('content type must be application/octet-stream', 415);
  }

  let decoded: Internal.ExecutionsRequest;
  try {
    decoded = fromBinary(
      Internal.ExecutionsRequestSchema,
      new Uint8Array(await request.arrayBuffer()),
    );
  } catch (error) {
    return executionError(describeError(error, 'request body could not be read'), 400);
  }

  try {
    const response = manager.sync(request, decoded);
    return binaryResponse(toBinary(Internal.ExecutionsResponseSchema, response));
  } catch (error) {
    if (error instanceof ExecutionRequestError) {
      return executionError(error.message, 400);
    }
    return executionError('internal server error', 500);
  }
}

export function executionError(message: string, status: number, headers?: HeadersInit): Response {
  return binaryResponse(
    toBinary(
      Internal.ErrorResponseSchema,
      create(Internal.ErrorResponseSchema, {
        error: message,
      }),
    ),
    status,
    headers,
  );
}

function binaryResponse(
  body: Uint8Array,
  status = 200,
  headers?: HeadersInit,
): Response {
  const responseHeaders = new Headers(headers);
  responseHeaders.set('content-type', EXECUTIONS_CONTENT_TYPE);
  return new Response(new Blob([body.slice().buffer as ArrayBuffer]), {
    status,
    headers: responseHeaders,
  });
}

function mediaType(value: string | null): string | undefined {
  return value?.split(';', 1)[0].trim().toLowerCase();
}

function describeError(error: unknown, fallback: string): string {
  return error instanceof Error && error.message !== '' ? error.message : fallback;
}
