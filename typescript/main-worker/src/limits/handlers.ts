import { jsonError, jsonResponse } from '../auth/http.ts';
import type { ExecutionStatsHub } from '../stats/handlers.ts';

/** Serves the Main Worker's latest authoritative, non-durable limit snapshot. */
export function handlePlayerLimits(
  request: Request,
  playerId: string,
  stats: ExecutionStatsHub,
): Response {
  if (request.method !== 'GET') {
    return new Response(null, { status: 405, headers: { allow: 'GET' } });
  }

  const playerLimits = stats.playerLimits(playerId);
  if (playerLimits === undefined) {
    return jsonError('limits are not available yet', 503);
  }

  return jsonResponse({
    runningExecutions: {
      ...playerLimits.runningExecutions,
    },
    totalExecutionStateBytes: {
      value: Number(playerLimits.executionStateBytes),
      limit: Number(playerLimits.executionStateLimit),
    },
    burstChatTokens: playerLimits.burstChatTokens,
    slowChatTokens: playerLimits.slowChatTokens,
    ...(playerLimits.logTokens === undefined ? {} : { logTokens: playerLimits.logTokens }),
    updatedAt: playerLimits.updatedAt,
  });
}
