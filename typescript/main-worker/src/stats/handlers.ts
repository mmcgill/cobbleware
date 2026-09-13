import { create, fromBinary, toBinary } from '@cobbleware/core';
import * as Internal from '@cobbleware/core/internal';
import type { AppConfig } from '../config.ts';
import { requireModAuth, requireSameOrigin } from '../auth/middleware.ts';

/** Retains the latest non-durable raw tick per player and the one permitted player stream. */
export class ExecutionStatsHub {
  private readonly reports = new Map<string, Internal.ExecutionStatsTick>();
  private readonly streams = new Map<string, WebSocket>();
  private modStream: WebSocket | undefined;
  private lastModTick = 0n;

  openMod(socket: WebSocket): void {
    const previous = this.modStream;
    this.modStream = socket;
    this.lastModTick = 0n;
    if (previous !== undefined && previous !== socket) {
      try {
        previous.close(1012, 'replaced by a newer Mod statistics stream');
      } catch {
        // The old connection is already closed.
      }
    }
    socket.binaryType = 'arraybuffer';
    socket.onmessage = (event) => {
      if (this.modStream !== socket || !(event.data instanceof ArrayBuffer)) {
        rejectSocket(
          socket,
          'invalid-execution-stats-frame',
          'Execution statistics must use binary frames.',
        );
        return;
      }
      try {
        const report = fromBinary(Internal.ExecutionStatsTickSchema, new Uint8Array(event.data));
        validate(report);
        if (report.tickSequence <= this.lastModTick) {
          throw new Error('tick sequence must increase within one Mod stream');
        }
        this.lastModTick = report.tickSequence;
        this.accept(report);
      } catch (error) {
        rejectSocket(
          socket,
          'invalid-execution-stats-frame',
          describeError(error, 'Invalid execution statistics.'),
        );
      }
    };
    socket.onclose = () => this.releaseMod(socket);
    socket.onerror = () => this.releaseMod(socket);
  }

  open(playerId: string, socket: WebSocket): void {
    if (this.streams.has(playerId)) {
      socket.onopen = () =>
        rejectSocket(
          socket,
          'execution-stats-stream-already-open',
          'Execution statistics are already open in another browser window.',
        );
      return;
    }
    this.streams.set(playerId, socket);
    socket.onopen = () => {
      const cached = this.reports.get(playerId);
      if (cached !== undefined) this.send(playerId, cached);
    };
    socket.onmessage = () =>
      rejectSocket(
        socket,
        'execution-stats-stream-read-only',
        'Execution statistics streams are read-only.',
      );
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

  private accept(report: Internal.ExecutionStatsTick): void {
    for (const player of report.players) {
      const playerId = uuidString(player.playerId!);
      const snapshot = playerSnapshot(report, player);
      this.reports.set(playerId, snapshot);
      this.send(playerId, snapshot);
    }
  }

  private send(playerId: string, snapshot: Internal.ExecutionStatsTick): void {
    const socket = this.streams.get(playerId);
    if (socket === undefined || socket.readyState !== WebSocket.OPEN) return;
    try {
      socket.send(toBinary(Internal.ExecutionStatsTickSchema, snapshot));
    } catch {
      this.release(playerId, socket);
    }
  }

  private release(playerId: string, socket: WebSocket): void {
    if (this.streams.get(playerId) === socket) this.streams.delete(playerId);
  }

  private releaseMod(socket: WebSocket): void {
    if (this.modStream === socket) {
      this.modStream = undefined;
      this.lastModTick = 0n;
    }
  }
}

export function handleModExecutionStats(
  request: Request,
  config: AppConfig,
  hub: ExecutionStatsHub,
): Response {
  if (request.method !== 'GET') {
    return new Response(null, { status: 405, headers: { allow: 'GET' } });
  }
  if (!isWebSocketUpgrade(request)) {
    return new Response(null, { status: 426, headers: { upgrade: 'websocket' } });
  }
  const authError = requireModAuth(request, config);
  if (authError !== null) {
    return rejectedUpgrade(
      request,
      authError.status === 401 ? 'unauthorized' : 'server-authentication-unavailable',
      authError.status === 401
        ? 'Mod authentication failed.'
        : 'Server authentication is not configured.',
    );
  }
  const { response, socket } = Deno.upgradeWebSocket(request);
  hub.openMod(socket);
  return response;
}

export function handlePlayerExecutionStats(
  request: Request,
  playerId: string | undefined,
  hub: ExecutionStatsHub,
  rejection?: { code: string; error: string },
): Response {
  if (request.method !== 'GET') {
    return new Response(null, { status: 405, headers: { allow: 'GET' } });
  }
  if (!isWebSocketUpgrade(request)) {
    return new Response(null, { status: 426, headers: { upgrade: 'websocket' } });
  }
  if (rejection !== undefined) return rejectedUpgrade(request, rejection.code, rejection.error);
  const originError = requireSameOrigin(request);
  if (originError !== null) {
    return rejectedUpgrade(
      request,
      'cross-origin-request-rejected',
      'A same-origin request is required.',
    );
  }
  const { response, socket } = Deno.upgradeWebSocket(request);
  hub.open(playerId!, socket);
  return response;
}

export function rejectedUpgrade(request: Request, code: string, error: string): Response {
  const { response, socket } = Deno.upgradeWebSocket(request);
  socket.onopen = () => rejectSocket(socket, code, error);
  return response;
}

function rejectSocket(socket: WebSocket, code: string, error: string): void {
  try {
    socket.send(JSON.stringify({ code, error }));
  } finally {
    socket.close(1008, code);
  }
}

function playerSnapshot(
  report: Internal.ExecutionStatsTick,
  player: Internal.ExecutionStatsTick_PlayerStats,
): Internal.ExecutionStatsTick {
  return create(Internal.ExecutionStatsTickSchema, {
    sampledAtUnixMillis: report.sampledAtUnixMillis,
    tickSequence: report.tickSequence,
    players: [player],
  });
}

function validate(report: Internal.ExecutionStatsTick): void {
  if (report.sampledAtUnixMillis === 0n || report.tickSequence === 0n) {
    throw new Error('sample time and tick sequence must be nonzero');
  }
  const players = new Set<string>();
  for (const player of report.players) {
    if (player.playerId === undefined) throw new Error('player stats must contain a player id');
    const playerId = uuidString(player.playerId);
    if (players.has(playerId)) throw new Error('player ids must be unique');
    players.add(playerId);
    validatePlayer(player);
  }
}

function validatePlayer(player: Internal.ExecutionStatsTick_PlayerStats): void {
  const events = new Set<string>();
  for (const metric of player.events) {
    const key = `${cobbleputerKey(metric.cobbleputer)}:${metric.type}`;
    if (!validEventType(metric.type) || events.has(key)) {
      throw new Error('event measurements must have unique valid keys');
    }
    events.add(key);
  }
  const syncs = new Set<string>();
  for (const metric of player.eventsPerSync) {
    const key = cobbleputerKey(metric.cobbleputer);
    if (syncs.has(key)) {
      throw new Error('events-per-sync measurements must have unique Cobbleputers');
    }
    syncs.add(key);
    if (
      (metric.count === 0n && (metric.total !== 0n || metric.max !== 0n)) ||
      metric.max > metric.total || (metric.count !== 0n && metric.total > metric.count * metric.max)
    ) {
      throw new Error('events-per-sync summary is inconsistent');
    }
  }
  const effects = new Set<string>();
  for (const metric of player.effects) {
    const key = `${cobbleputerKey(metric.cobbleputer)}:${metric.type}`;
    if (!validEffectType(metric.type) || effects.has(key)) {
      throw new Error('effect measurements must have unique valid keys');
    }
    effects.add(key);
    if (metric.failed > metric.attempted) {
      throw new Error('failed effects cannot exceed attempted effects');
    }
  }
  const states = new Set<string>();
  for (const metric of player.cobbleputerStates) {
    const key = cobbleputerKey(metric.cobbleputer);
    if (!validLifecycleState(metric.state) || states.has(key)) {
      throw new Error('Cobbleputer states must have unique valid Cobbleputers');
    }
    states.add(key);
  }
}

function cobbleputerKey(value: Internal.ExecutionStatsTick_CobbleputerRef | undefined): string {
  if (
    value?.cobbleputerId === undefined || value.position?.dimension === undefined ||
    value.position.position === undefined ||
    value.position.dimension.ns === '' || value.position.dimension.path === ''
  ) {
    throw new Error('measurement must contain a Cobbleputer id and position');
  }
  return `${
    uuidString(value.cobbleputerId)
  }:${value.position.dimension.ns}:${value.position.dimension.path}:${value.position.position.x}:${value.position.position.y}:${value.position.position.z}`;
}

function validEventType(value: Internal.InstrumentationEventType): boolean {
  return value >= Internal.InstrumentationEventType.STARTED &&
    value <= Internal.InstrumentationEventType.WIRELESS_OBSERVER_UNPAIRED;
}

function validEffectType(value: Internal.InstrumentationEffectType): boolean {
  return value >= Internal.InstrumentationEffectType.SEND_CHAT &&
    value <= Internal.InstrumentationEffectType.SET_REDSTONE_POWER;
}

function validLifecycleState(value: Internal.InstrumentationLifecycleState): boolean {
  return value === Internal.InstrumentationLifecycleState.RUNNING ||
    value === Internal.InstrumentationLifecycleState.STOPPED;
}

function uuidString(value: { high: bigint; low: bigint }): string {
  const hex = `${value.high.toString(16).padStart(16, '0')}${
    value.low.toString(16).padStart(16, '0')
  }`;
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${
    hex.slice(20)
  }`;
}

function isWebSocketUpgrade(request: Request): boolean {
  return request.headers.get('upgrade')?.toLowerCase() === 'websocket';
}

function describeError(error: unknown, fallback: string): string {
  return error instanceof Error && error.message !== '' ? error.message : fallback;
}
