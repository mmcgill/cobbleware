import type { AppConfig } from '../config.ts';
import { emptyResponse, jsonError, jsonResponse, readJson } from './http.ts';
import { requireModAuth, requireSameOrigin } from './middleware.ts';
import type { AuthStore, PlayerIdentity } from './types.ts';
import {
  ModAuthenticationRequestSchema,
  normalizeAuthCode,
  normalizePlayerUUID,
  PlayerAuthenticationRequestSchema,
} from './validation.ts';

export async function handleModAuthentication(
  request: Request,
  config: AppConfig,
  store: AuthStore,
): Promise<Response> {
  const authError = requireModAuth(request, config);
  if (authError !== null) {
    return authError;
  }

  if (request.method !== 'POST') {
    return methodNotAllowed('POST');
  }

  let body: unknown;
  try {
    body = await readJson(request, config.maxJsonBodyBytes);
  } catch {
    return jsonError('invalid request body', 400);
  }

  const parsed = ModAuthenticationRequestSchema.safeParse(body);
  if (!parsed.success) {
    return jsonError('invalid authentication request', 400);
  }
  if (normalizeAuthCode(parsed.data.code) === null) {
    return jsonError('invalid authentication request', 400);
  }

  try {
    const result = await store.bindPending(parsed.data.code, {
      playerUUID: normalizePlayerUUID(parsed.data.playerUUID),
      playerProfileName: parsed.data.playerProfileName,
    });

    if (result === 'conflict') {
      return jsonError('authentication code is already associated with another player', 400);
    }

    return emptyResponse(201);
  } catch {
    return jsonError('authentication request could not be completed', 500);
  }
}

export async function handlePlayerAuthentication(
  request: Request,
  config: AppConfig,
  store: AuthStore,
): Promise<Response> {
  if (request.method !== 'POST') {
    return methodNotAllowed('POST');
  }

  let body: unknown;
  try {
    body = await readJson(request, config.maxJsonBodyBytes);
  } catch {
    return jsonError('invalid request body', 400);
  }

  const parsed = PlayerAuthenticationRequestSchema.safeParse(body);
  if (!parsed.success) {
    return jsonError('invalid authentication code', 400);
  }

  try {
    const consumed = await store.consumePending(parsed.data.code);
    if (consumed === null) {
      return jsonError('invalid authentication code', 400);
    }

    return emptyResponse(201, {
      'set-cookie':
        `${config.cookieName}=${consumed.sessionToken}; Path=/; HttpOnly; SameSite=Lax; Max-Age=${config.sessionCookieMaxAgeSeconds}`,
    });
  } catch {
    return jsonError('authentication request could not be completed', 500);
  }
}

export function handlePlayerWhoAmI(request: Request, player: PlayerIdentity): Response {
  if (request.method !== 'GET') {
    return methodNotAllowed('GET');
  }

  return jsonResponse({ playerProfileName: player.playerProfileName });
}

export async function handlePlayerLogout(
  request: Request,
  config: AppConfig,
  store: AuthStore,
  sessionToken: string,
): Promise<Response> {
  if (request.method !== 'POST') {
    return methodNotAllowed('POST');
  }

  const originError = requireSameOrigin(request);
  if (originError !== null) {
    return originError;
  }

  try {
    await store.revokeSession(sessionToken);
    return emptyResponse(204, {
      'set-cookie': `${config.cookieName}=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0`,
    });
  } catch {
    return jsonError('logout request could not be completed', 500);
  }
}

function methodNotAllowed(allowedMethod: string): Response {
  return new Response(null, {
    status: 405,
    headers: { allow: allowedMethod },
  });
}
