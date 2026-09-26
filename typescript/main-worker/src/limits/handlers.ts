import { jsonError, jsonResponse } from '../auth/http.ts';
import type { ExecutionLogHub } from '../logs/handlers.ts';

/** Serves the Main Worker's latest authoritative, non-durable limit snapshot. */
export function handlePlayerLimits(
  request: Request,
  playerId: string,
  logs: ExecutionLogHub,
): Response {
  if (request.method !== 'GET') {
    return new Response(null, { status: 405, headers: { allow: 'GET' } });
  }

  const runningExecutions = logs.runningExecutions(playerId);
  if (runningExecutions === undefined) {
    return jsonError('running execution limits are not available yet', 503);
  }

  return jsonResponse({
    runningExecutions: {
      used: runningExecutions.used,
      limit: runningExecutions.limit,
    },
    updatedAt: runningExecutions.updatedAt,
  });
}
