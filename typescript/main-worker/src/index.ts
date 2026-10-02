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
import {
  handlePlayerProgramStartStates,
  handleProgram,
  handleProgramCollection,
  handleProgramStep,
} from './programs/handlers.ts';
import { DurableProgramStore } from './programs/store.ts';
import { PROGRAM_ID_PATTERN, type ProgramStore } from './programs/types.ts';
import { type ProgramOperations, RuntimeProgramOperations } from './programs/worker.ts';
import { normalizePlayerUUID } from './auth/validation.ts';
import { handleExecutions } from './executions/handlers.ts';
import { ExecutionManager } from './executions/manager.ts';
import {
  ExecutionStatsHub,
  handleModExecutionStats,
  handlePlayerExecutionStats,
} from './stats/handlers.ts';
import {
  ExecutionLogHub,
  handleModExecutionLogs,
  handlePlayerExecutionLogs,
} from './logs/handlers.ts';
import { handlePlayerLimits } from './limits/handlers.ts';

const HELLO_BODY = JSON.stringify({ message: 'hello' });

export type AppOptions = {
  config?: AppConfig;
  store?: AuthStore;
  programStore?: ProgramStore;
  programOperations?: ProgramOperations;
  executionManager?: ExecutionManager;
  executionStatsHub?: ExecutionStatsHub;
  executionLogHub?: ExecutionLogHub;
};

export async function createHandler(
  options: AppOptions = {},
): Promise<(request: Request) => Promise<Response>> {
  const config = options.config ?? configFromEnv();
  const store = options.store ?? await DurableAuthStore.open(config);
  const programStore = options.programStore ?? new DurableProgramStore(config);
  const programOperations = options.programOperations ?? new RuntimeProgramOperations(
    config.playerMemoryQuotaBytes,
  );
  const executionManager = options.executionManager ?? new ExecutionManager(
    programStore,
    config.playerMemoryQuotaBytes,
  );
  const executionStatsHub = options.executionStatsHub ?? new ExecutionStatsHub({
    memoryQuotaBytes: config.playerMemoryQuotaBytes,
  });
  const executionLogHub = options.executionLogHub ?? new ExecutionLogHub();

  return async (request) => {
    try {
      return await routeRequest(
        request,
        config,
        store,
        programStore,
        programOperations,
        executionManager,
        executionStatsHub,
        executionLogHub,
      );
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
  programOperations: ProgramOperations,
  executionManager: ExecutionManager,
  executionStatsHub: ExecutionStatsHub,
  executionLogHub: ExecutionLogHub,
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
    if (authError !== null) return authError;
    const response = await handlePlayerLogout(request, config, store, context.sessionToken!);
    if (response.status === 204) {
      executionStatsHub.closePlayer(context.player!.playerUUID);
      executionLogHub.closePlayer(context.player!.playerUUID);
    }
    return response;
  }

  if (pathname === '/playerapi/v1/execution-stats') {
    const context: RequestContext = {};
    const authError = await requirePlayerAuth(request, config, store, context, 'api');
    if (authError !== null) {
      if (request.headers.get('upgrade')?.toLowerCase() !== 'websocket') return authError;
      return handlePlayerExecutionStats(request, undefined, executionStatsHub, {
        code: 'unauthorized',
        error: 'Player authentication failed.',
      });
    }
    return handlePlayerExecutionStats(request, context.player!.playerUUID, executionStatsHub);
  }

  if (pathname === '/playerapi/v1/logs') {
    const context: RequestContext = {};
    const authError = await requirePlayerAuth(request, config, store, context, 'api');
    if (authError !== null) {
      if (request.headers.get('upgrade')?.toLowerCase() !== 'websocket') return authError;
      return handlePlayerExecutionLogs(request, undefined, executionLogHub, {
        code: 'unauthorized',
        error: 'Player authentication failed.',
      });
    }
    return handlePlayerExecutionLogs(request, context.player!.playerUUID, executionLogHub);
  }

  if (pathname === '/playerapi/v1/limits') {
    const context: RequestContext = {};
    const authError = await requirePlayerAuth(request, config, store, context, 'api');
    return authError ??
      handlePlayerLimits(request, context.player!.playerUUID, executionLogHub, executionStatsHub);
  }

  if (pathname === '/playerapi/v1/programs') {
    const context: RequestContext = {};
    const authError = await requirePlayerAuth(request, config, store, context, 'api');
    return authError ?? handleProgramCollection(
      request,
      programStore,
      programOperations,
      context.player!.playerUUID,
      (playerId, programId) => executionManager.activeProgramChanged(playerId, programId),
    );
  }

  const programStartStatesMatch = /^\/playerapi\/v1\/programs\/([^/]+)\/startStates$/.exec(
    pathname,
  );
  if (programStartStatesMatch !== null) {
    const context: RequestContext = {};
    const authError = await requirePlayerAuth(request, config, store, context, 'api');
    if (authError !== null) {
      return authError;
    }

    let programId: string;
    try {
      programId = decodeURIComponent(programStartStatesMatch[1]);
    } catch {
      return new Response(null, { status: 404 });
    }
    if (!PROGRAM_ID_PATTERN.test(programId)) {
      return new Response(null, { status: 404 });
    }
    return handlePlayerProgramStartStates(
      request,
      programStore,
      programOperations,
      context.player!.playerUUID,
      programId,
    );
  }

  const programStepMatch = /^\/playerapi\/v1\/programs\/([^/]+)\/step$/.exec(pathname);
  if (programStepMatch !== null) {
    const context: RequestContext = {};
    const authError = await requirePlayerAuth(request, config, store, context, 'api');
    if (authError !== null) {
      return authError;
    }

    let programId: string;
    try {
      programId = decodeURIComponent(programStepMatch[1]);
    } catch {
      return new Response(null, { status: 404 });
    }
    if (!PROGRAM_ID_PATTERN.test(programId)) {
      return new Response(null, { status: 404 });
    }
    return handleProgramStep(
      request,
      programStore,
      config.playerMemoryQuotaBytes,
      context.player!.playerUUID,
      programId,
    );
  }

  if (pathname.startsWith('/modapi/v1/')) {
    if (pathname === '/modapi/v1/authentication') {
      return handleModAuthentication(request, config, store);
    }
    if (pathname === '/modapi/v1/executions') {
      return await handleExecutions(request, config, executionManager);
    }
    if (pathname === '/modapi/v1/execution-stats') {
      return handleModExecutionStats(request, config, executionStatsHub);
    }
    if (pathname === '/modapi/v1/logs') {
      return handleModExecutionLogs(request, config, executionLogHub);
    }
    const programMatch = /^\/modapi\/v1\/players\/([^/]+)\/program$/.exec(pathname);
    if (programMatch !== null) {
      const authError = requireModAuth(request, config);
      if (authError !== null) {
        return authError;
      }

      let playerUUID: string;
      try {
        playerUUID = normalizePlayerUUID(decodeURIComponent(programMatch[1]));
      } catch {
        return new Response(null, { status: 404 });
      }
      return handleProgram(request, programStore, programOperations, playerUUID);
    }
    const authError = requireModAuth(request, config);
    return authError ?? new Response(null, { status: 404 });
  }

  if (pathname.startsWith('/playerapi/v1/')) {
    const context: RequestContext = {};
    const authError = await requirePlayerAuth(request, config, store, context, 'api');
    return authError ?? new Response(null, { status: 404 });
  }

  if (pathname === '/auth.html' || !pathname.toLowerCase().endsWith('.html')) {
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
