import { jsonError, jsonResponse } from '../auth/http.ts';
import type { ExecutionLogHub } from '../logs/handlers.ts';
import type { ExecutionStatsHub } from '../stats/handlers.ts';

/** Serves the Main Worker's latest authoritative, non-durable limit snapshot. */
export function handlePlayerLimits(
  request: Request,
  playerId: string,
  logs: ExecutionLogHub,
  stats: ExecutionStatsHub,
): Response {
  if (request.method !== 'GET') {
    return new Response(null, { status: 405, headers: { allow: 'GET' } });
  }

  const runningExecutions = logs.runningExecutions(playerId);
  const executionStateBytes = stats.executionStateBytes(playerId);
  if (runningExecutions === undefined || executionStateBytes === undefined) {
    return jsonError('running execution limits are not available yet', 503);
  }

  return jsonResponse({
    runningExecutions: {
      used: runningExecutions.used,
      limit: runningExecutions.limit,
    },
    totalExecutionStateBytes: {
      used: Number(executionStateBytes.used),
      limit: Number(runningExecutions.totalExecutionStateLimit),
      updatedAt: executionStateBytes.updatedAt,
    },
    updatedAt: runningExecutions.updatedAt,
  });
}
