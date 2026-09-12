import { create, fromBinary, toBinary } from '@cobbleware/core';
import * as Internal from '@cobbleware/core/internal';
import type { AppConfig } from '../config.ts';
import { requireModAuth, requireSameOrigin } from '../auth/middleware.ts';

export const EXECUTION_STATS_CONTENT_TYPE = 'application/octet-stream';

/** Retains the latest non-durable Mod report and the one permitted player stream. */
export class ExecutionStatsHub {
  private readonly reports = new Map<string, Internal.ExecutionStats>();
  private readonly streams = new Map<string, WebSocket>();

  accept(report: Internal.ExecutionStats): void {
    validate(report);
    const nextReports = new Map<string, Internal.ExecutionStats>();
    for (const player of report.players) {
      const playerId = uuidString(player.playerId!);
      nextReports.set(playerId, playerSnapshot(report, player));
    }
    this.reports.clear();
    nextReports.forEach((snapshot, playerId) => this.reports.set(playerId, snapshot));

    nextReports.forEach((snapshot, playerId) => this.send(playerId, snapshot));
  }

  open(playerId: string, socket: WebSocket): void {
    if (this.streams.has(playerId)) {
      socket.onopen = () => {
        try {
          socket.send(JSON.stringify({
            code: 'execution-stats-stream-already-open',
            error: 'Execution statistics are already open in another browser window.',
          }));
        } finally {
          socket.close(1008, 'execution-stats stream already open');
        }
      };
      return;
    }
    this.streams.set(playerId, socket);
    socket.onopen = () => {
      const cached = this.reports.get(playerId);
      if (cached !== undefined) this.send(playerId, cached);
    };
    socket.onmessage = () => socket.close(1008, 'execution-stats streams are read-only');
    socket.onclose = () => this.release(playerId, socket);
    socket.onerror = () => this.release(playerId, socket);
  }

  closePlayer(playerId: string): void {
    const socket = this.streams.get(playerId);
    if (socket === undefined) return;
    this.streams.delete(playerId);
    try {
      socket.close(1008, 'session invalidated');
    } catch {
      // The closed socket has already released its stream slot.
    }
  }

  private send(playerId: string, snapshot: Internal.ExecutionStats): void {
    const socket = this.streams.get(playerId);
    if (socket === undefined || socket.readyState !== WebSocket.OPEN) return;
    try {
      socket.send(toBinary(Internal.ExecutionStatsSchema, snapshot));
    } catch {
      this.release(playerId, socket);
    }
  }

  private release(playerId: string, socket: WebSocket): void {
    if (this.streams.get(playerId) === socket) this.streams.delete(playerId);
  }
}

export async function handleExecutionStatsReport(
  request: Request,
  config: AppConfig,
  hub: ExecutionStatsHub,
): Promise<Response> {
  if (request.method !== 'POST') return statsError('method must be POST', 405, { allow: 'POST' });
  const authError = requireModAuth(request, config);
  if (authError !== null) return statsErrorFromJson(authError);
  if (mediaType(request.headers.get('content-type')) !== EXECUTION_STATS_CONTENT_TYPE) {
    return statsError('content type must be application/octet-stream', 415);
  }

  let report: Internal.ExecutionStats;
  try {
    report = fromBinary(Internal.ExecutionStatsSchema, new Uint8Array(await request.arrayBuffer()));
    hub.accept(report);
  } catch (error) {
    return statsError(describeError(error, 'invalid execution stats report'), 400);
  }
  return new Response(null, { status: 204 });
}

export function handlePlayerExecutionStats(
  request: Request,
  playerId: string,
  hub: ExecutionStatsHub,
): Response {
  if (request.method !== 'GET') {
    return new Response(null, { status: 405, headers: { allow: 'GET' } });
  }
  if (request.headers.get('upgrade')?.toLowerCase() !== 'websocket') {
    return new Response(null, { status: 426, headers: { upgrade: 'websocket' } });
  }
  const originError = requireSameOrigin(request);
  if (originError !== null) return originError;
  const { response, socket } = Deno.upgradeWebSocket(request);
  hub.open(playerId, socket);
  return response;
}

function playerSnapshot(
  report: Internal.ExecutionStats,
  player: Internal.ExecutionStats_PlayerStats,
): Internal.ExecutionStats {
  return create(Internal.ExecutionStatsSchema, {
    sampledAtUnixMillis: report.sampledAtUnixMillis,
    windowTicks: report.windowTicks,
    retainedTickCount: report.retainedTickCount,
    players: [player],
  });
}

function validate(report: Internal.ExecutionStats): void {
  if (report.sampledAtUnixMillis === 0n || report.windowTicks === 0) {
    throw new Error('sample time and window length must be nonzero');
  }
  if (report.retainedTickCount > report.windowTicks) {
    throw new Error('retained tick count cannot exceed window length');
  }
  const players = new Set<string>();
  for (const player of report.players) {
    if (player.playerId === undefined) throw new Error('player stats must contain a player id');
    const playerId = uuidString(player.playerId);
    if (players.has(playerId)) throw new Error('player ids must be unique');
    players.add(playerId);
    if (player.effectsFailed > player.effectsAttempted) {
      throw new Error('failed effects cannot exceed attempted effects');
    }
    validateEventsPerSync(player);
  }
}

function validateEventsPerSync(player: Internal.ExecutionStats_PlayerStats): void {
  const { eventsPerSyncCount: count, eventsPerSyncTotal: total, eventsPerSyncMax: max } = player;
  if (count === 0n && (total !== 0n || max !== 0n)) {
    throw new Error('empty events-per-sync summaries must have zero total and maximum');
  }
  if (max > total || (count !== 0n && total > count * max)) {
    throw new Error('events-per-sync summary is inconsistent');
  }
}

function uuidString(value: { high: bigint; low: bigint }): string {
  const hex = `${value.high.toString(16).padStart(16, '0')}${
    value.low.toString(16).padStart(16, '0')
  }`;
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${
    hex.slice(20)
  }`;
}

function statsError(message: string, status: number, headers?: HeadersInit): Response {
  const responseHeaders = new Headers(headers);
  responseHeaders.set('content-type', EXECUTION_STATS_CONTENT_TYPE);
  return new Response(
    new Blob([
      toBinary(
        Internal.ErrorResponseSchema,
        create(Internal.ErrorResponseSchema, { error: message }),
      ),
    ]),
    {
      status,
      headers: responseHeaders,
    },
  );
}

function statsErrorFromJson(response: Response): Response {
  return statsError(
    response.status === 401 ? 'unauthorized' : 'server authentication is not configured',
    response.status,
    response.headers.get('www-authenticate') === null
      ? undefined
      : { 'www-authenticate': response.headers.get('www-authenticate')! },
  );
}

function mediaType(value: string | null): string | undefined {
  return value?.split(';', 1)[0].trim().toLowerCase();
}

function describeError(error: unknown, fallback: string): string {
  return error instanceof Error && error.message !== '' ? error.message : fallback;
}
