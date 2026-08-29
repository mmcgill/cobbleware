import { MAX_JSON_BODY_BYTES } from '../config.ts';
import { constantTimeEqual } from './crypto.ts';

export const JSON_CONTENT_TYPE = 'application/json; charset=utf-8';

export function jsonResponse(body: unknown, status = 200, headers?: HeadersInit): Response {
  const responseHeaders = new Headers(headers);
  responseHeaders.set('content-type', JSON_CONTENT_TYPE);
  return new Response(JSON.stringify(body), { status, headers: responseHeaders });
}

export function jsonError(error: string, status: number): Response {
  return jsonResponse({ error }, status);
}

export function emptyResponse(status: number, headers?: HeadersInit): Response {
  return new Response(null, { status, headers });
}

export function isJsonRequest(request: Request): boolean {
  const contentType = request.headers.get('content-type')?.split(';', 1)[0].trim().toLowerCase();
  return contentType === 'application/json';
}

export async function readJson(request: Request, maxBytes = MAX_JSON_BODY_BYTES): Promise<unknown> {
  const contentLength = request.headers.get('content-length');
  if (contentLength !== null && Number(contentLength) > maxBytes) {
    throw new Error('request body is too large');
  }

  if (!isJsonRequest(request)) {
    throw new Error('request content type must be application/json');
  }

  let text: string;
  try {
    text = await request.text();
  } catch {
    throw new Error('request body could not be read');
  }

  if (new TextEncoder().encode(text).byteLength > maxBytes) {
    throw new Error('request body is too large');
  }

  try {
    return JSON.parse(text);
  } catch {
    throw new Error('request body must be valid JSON');
  }
}

export function parseBearerToken(request: Request): string | null {
  const header = request.headers.get('authorization');
  if (header === null || !header.startsWith('Bearer ')) {
    return null;
  }

  const token = header.slice('Bearer '.length);
  return token.length > 0 && !token.includes(' ') ? token : null;
}

export function hasBearerToken(request: Request, expected: string): boolean {
  const header = request.headers.get('authorization');
  return header !== null && constantTimeEqual(header, `Bearer ${expected}`);
}

export function readCookie(request: Request, cookieName: string): string | null {
  const header = request.headers.get('cookie');
  if (header === null) {
    return null;
  }

  for (const part of header.split(';')) {
    const separator = part.indexOf('=');
    if (separator < 0) {
      continue;
    }
    const name = part.slice(0, separator).trim();
    const value = part.slice(separator + 1).trim();
    if (name === cookieName && value !== '') {
      return value;
    }
  }

  return null;
}
