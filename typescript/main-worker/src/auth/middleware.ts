import type { AppConfig } from '../config.ts';
import { emptyResponse, hasBearerToken, jsonError, readCookie } from './http.ts';
import type { AuthStore, PlayerIdentity } from './types.ts';

export type RequestContext = {
  player?: PlayerIdentity;
};

export type ProtectedRequestKind = 'api' | 'html';

export function requireSameOrigin(request: Request): Response | null {
  const origin = request.headers.get('origin');
  if (origin === null) {
    return jsonError('same-origin request required', 403);
  }

  try {
    if (new URL(origin).origin !== new URL(request.url).origin) {
      return jsonError('cross-origin request rejected', 403);
    }
  } catch {
    return jsonError('cross-origin request rejected', 403);
  }

  return null;
}

export function requireModAuth(request: Request, config: AppConfig): Response | null {
  if (config.apiKey === undefined || config.apiKey === '') {
    return jsonError('server authentication is not configured', 500);
  }

  if (!hasBearerToken(request, config.apiKey)) {
    return jsonErrorWithHeader('unauthorized', 401, 'WWW-Authenticate', 'Bearer');
  }

  return null;
}

export async function requirePlayerAuth(
  request: Request,
  config: AppConfig,
  store: AuthStore,
  context: RequestContext,
  kind: ProtectedRequestKind,
): Promise<Response | null> {
  const sessionToken = readCookie(request, config.cookieName);
  if (sessionToken !== null) {
    const identity = await store.findSession(sessionToken);
    if (identity !== null) {
      context.player = identity;
      return null;
    }
  }

  if (kind === 'html') {
    return emptyResponse(302, { location: '/auth.html' });
  }

  return jsonErrorWithHeader(
    'unauthorized',
    401,
    'WWW-Authenticate',
    'Cobbleware realm="player", location="/auth.html"',
  );
}

function jsonErrorWithHeader(
  error: string,
  status: number,
  name: string,
  value: string,
): Response {
  const response = jsonError(error, status);
  response.headers.set(name, value);
  return response;
}
