import { type AppConfig, configFromEnv } from './config.ts';
import {
  handleModAuthentication,
  handlePlayerAuthentication,
  handlePlayerLogout,
  handlePlayerWhoAmI,
} from './auth/handlers.ts';
import { type RequestContext, requireModAuth, requirePlayerAuth } from './auth/middleware.ts';
import { DurableAuthStore } from './auth/store.ts';
import type { AuthStore } from './auth/types.ts';
import { serveStatic } from './static.ts';
import { jsonError } from './auth/http.ts';
import { handleProgramCollection, handleProgramDelete } from './programs/handlers.ts';
import { DurableProgramStore } from './programs/store.ts';
import type { ProgramStore } from './programs/types.ts';

const HELLO_BODY = JSON.stringify({ message: 'hello' });

export type AppOptions = {
  config?: AppConfig;
  store?: AuthStore;
  programStore?: ProgramStore;
};

export async function createHandler(
  options: AppOptions = {},
): Promise<(request: Request) => Promise<Response>> {
  const config = options.config ?? configFromEnv();
  const store = options.store ?? await DurableAuthStore.open(config);
  const programStore = options.programStore ?? new DurableProgramStore(config);

  return async (request) => {
    try {
      return await routeRequest(request, config, store, programStore);
    } catch {
      return jsonError('internal server error', 500);
    }
  };
}

async function routeRequest(
  request: Request,
  config: AppConfig,
  store: AuthStore,
  programStore: ProgramStore,
): Promise<Response> {
  const { pathname } = new URL(request.url);

  if (pathname === '/hello') {
    if (request.method !== 'GET') {
      return new Response(null, { status: 405, headers: { allow: 'GET' } });
    }
    return new Response(HELLO_BODY, {
      status: 200,
      headers: { 'content-type': 'application/json' },
    });
  }

  if (pathname === '/') {
    return new Response(null, { status: 302, headers: { location: '/index.html' } });
  }

  if (pathname === '/authenticate') {
    return new Response(null, { status: 302, headers: { location: '/auth.html' } });
  }

  if (pathname === '/playerapi/v1/authenticate') {
    return handlePlayerAuthentication(request, config, store);
  }

  if (pathname === '/playerapi/v1/whoami') {
    const context: RequestContext = {};
    const authError = await requirePlayerAuth(request, config, store, context, 'api');
    return authError ?? handlePlayerWhoAmI(request, context.player!);
  }

  if (pathname === '/playerapi/v1/logout') {
    const context: RequestContext = {};
    const authError = await requirePlayerAuth(request, config, store, context, 'api');
    return authError ?? handlePlayerLogout(request, config, store, context.sessionToken!);
  }

  if (pathname === '/playerapi/v1/programs') {
    const context: RequestContext = {};
    const authError = await requirePlayerAuth(request, config, store, context, 'api');
    return authError ?? handleProgramCollection(request, programStore, context.player!.playerUUID);
  }

  const programIdMatch = /^\/playerapi\/v1\/programs\/([^/]+)$/.exec(pathname);
  if (programIdMatch !== null) {
    const context: RequestContext = {};
    const authError = await requirePlayerAuth(request, config, store, context, 'api');
    if (authError !== null) {
      return authError;
    }

    let programId: string;
    try {
      programId = decodeURIComponent(programIdMatch[1]);
    } catch {
      return jsonError('program not found', 404);
    }
    return handleProgramDelete(request, programStore, context.player!.playerUUID, programId);
  }

  if (pathname.startsWith('/modapi/v1/')) {
    if (pathname === '/modapi/v1/authentication') {
      return handleModAuthentication(request, config, store);
    }
    const authError = requireModAuth(request, config);
    return authError ?? new Response(null, { status: 404 });
  }

  if (pathname.startsWith('/playerapi/v1/')) {
    const context: RequestContext = {};
    const authError = await requirePlayerAuth(request, config, store, context, 'api');
    return authError ?? new Response(null, { status: 404 });
  }

  if (pathname === '/auth.html') {
    return serveStatic(request);
  }

  const context: RequestContext = {};
  const authError = await requirePlayerAuth(request, config, store, context, 'html');
  return authError ?? serveStatic(request);
}

const defaultHandlerPromise = createHandler();

export function handleRequest(request: Request): Promise<Response> {
  return defaultHandlerPromise.then((handler) => handler(request));
}

if (import.meta.main) {
  Deno.serve(handleRequest);
}
