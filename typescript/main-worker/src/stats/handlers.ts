import { create, fromBinary, toBinary } from '@cobbleware/core';
import * as Internal from '@cobbleware/core/internal';
import type { AppConfig } from '../config.ts';
import { requireModAuth, requireSameOrigin } from '../auth/middleware.ts';
import { constantTimeEqual } from '../auth/crypto.ts';
import { playerWorkerServicePathPrefix } from '../programs/store.ts';

type MemoryStats =
  | Map<string, CobblewareWorkerHeapStatisticsWithServicePath>
  | Record<string, CobblewareWorkerHeapStatisticsWithServicePath>;
type MemoryStatsProvider = () => Promise<MemoryStats>;

export type ExecutionStatsHubOptions = {
  memoryQuotaBytes: number;
  memoryStats?: MemoryStatsProvider;
};

export type LogTokenBucketSnapshot = {
  available: number;
  capacity: number;
};

export type PlayerLimitsSnapshot = {
  runningExecutions: { value: number; limit: number };
  executionStateBytes: bigint;
  executionStateLimit: bigint;
  burstChatTokens: { value: number; limit: number };
  slowChatTokens: { value: number; limit: number };
  logTokens?: { value: number; limit: number };
  updatedAt: string;
};

/** Retains the latest non-durable raw tick per player and the one permitted player stream. */
export class ExecutionStatsHub {
  private readonly reports = new Map<string, Internal.ExecutionStatsTick>();
  private readonly streams = new Map<string, WebSocket>();
  private readonly logTokenBuckets = new Map<string, LogTokenBucketSnapshot>();
  private readonly memoryStats: MemoryStatsProvider;
  private modStream: WebSocket | undefined;
  private lastModTick = 0n;
  private modGeneration = 0;
  private modProcessing = Promise.resolve();

  constructor(private readonly options: ExecutionStatsHubOptions) {
    this.memoryStats = options.memoryStats ?? (() => EdgeRuntime.userWorkers.memStats());
  }

  openMod(socket: WebSocket): void {
    const previous = this.modStream;
    this.modStream = socket;
    this.lastModTick = 0n;
    const generation = ++this.modGeneration;
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
      this.enqueueModReport(socket, generation, event.data);
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

  /** Records the Event Worker's latest non-durable program-log token-bucket snapshot. */
  recordLogTokens(playerId: string, snapshot: LogTokenBucketSnapshot): void {
    this.logTokenBuckets.set(playerId, { ...snapshot });
  }

  /** Returns the latest enriched player-scoped values used by the Limits endpoint. */
  playerLimits(playerId: string): PlayerLimitsSnapshot | undefined {
    const report = this.reports.get(playerId);
    const player = report?.players[0];
    if (
      player === undefined || player.maxRunningExecutions <= 0 ||
      player.runningExecutions > player.maxRunningExecutions ||
      player.maxTotalExecutionStateBytes <= 0n ||
      player.maxBurstChatTokens <= 0 || player.maxSlowChatTokens <= 0 ||
      player.burstChatTokens > player.maxBurstChatTokens ||
      player.slowChatTokens > player.maxSlowChatTokens ||
      player.logTokensAvailable !== (player.logTokens !== undefined)
    ) {
      return undefined;
    }
    const logTokens = player.logTokens;
    if (logTokens !== undefined && !validLogTokenBucket(logTokens)) return undefined;
    let executionStateBytes = 0n;
    for (const state of player.cobbleputerStates) executionStateBytes += state.stateSizeBytes;
    return {
      runningExecutions: { value: player.runningExecutions, limit: player.maxRunningExecutions },
      executionStateBytes,
      executionStateLimit: player.maxTotalExecutionStateBytes,
      burstChatTokens: { value: player.burstChatTokens, limit: player.maxBurstChatTokens },
      slowChatTokens: { value: player.slowChatTokens, limit: player.maxSlowChatTokens },
      ...(logTokens === undefined
        ? {}
        : { logTokens: { value: logTokens.available, limit: logTokens.capacity } }),
      updatedAt: new Date(Number(report!.sampledAtUnixMillis)).toISOString(),
    };
  }

  openLogStats(socket: WebSocket): void {
    socket.binaryType = 'arraybuffer';
    socket.onmessage = (event) => {
      if (!(event.data instanceof ArrayBuffer)) {
        rejectSocket(socket, 'invalid-log-stats-frame', 'Log statistics must use binary frames.');
        return;
      }
      try {
        const stats = fromBinary(Internal.LogStatsSchema, new Uint8Array(event.data));
        if (stats.playerId === undefined || !validLogTokenBucket(stats.logTokens)) {
          throw new Error('log statistics must contain a player and valid token bucket');
        }
        this.recordLogTokens(uuidString(stats.playerId), stats.logTokens);
      } catch (error) {
        rejectSocket(
          socket,
          'invalid-log-stats-frame',
          describeError(error, 'Invalid log statistics.'),
        );
      }
    };
  }

  private enqueueModReport(socket: WebSocket, generation: number, data: ArrayBuffer): void {
    this.modProcessing = this.modProcessing.then(async () => {
      if (this.modStream !== socket || this.modGeneration !== generation) return;

      let report: Internal.ExecutionStatsTick;
      try {
        report = fromBinary(Internal.ExecutionStatsTickSchema, new Uint8Array(data));
        validate(report);
        if (report.tickSequence <= this.lastModTick) {
          throw new Error('tick sequence must increase within one Mod stream');
        }
        this.lastModTick = report.tickSequence;
      } catch (error) {
        rejectSocket(
          socket,
          'invalid-execution-stats-frame',
          describeError(error, 'Invalid execution statistics.'),
        );
        return;
      }

      let memoryUsage = new Map<string, bigint>();
      try {
        memoryUsage = memoryUsageByPlayer(
          await this.memoryStats(),
          report.players.map((player) => uuidString(player.playerId!)),
        );
      } catch (error) {
        console.error('Unable to collect user-worker memory statistics:', error);
      }
      if (this.modStream !== socket || this.modGeneration !== generation) return;
      this.accept(report, memoryUsage);
    });
  }

  private accept(
    report: Internal.ExecutionStatsTick,
    memoryUsage: ReadonlyMap<string, bigint>,
  ): void {
    for (const player of report.players) {
      const playerId = uuidString(player.playerId!);
      const snapshot = playerSnapshot(
        report,
        player,
        memoryUsage.get(playerId) ?? 0n,
        BigInt(this.options.memoryQuotaBytes),
        this.logTokenBuckets.get(playerId),
      );
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
      this.modGeneration += 1;
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

/** Accepts Event Worker token-bucket telemetry separately from displayable execution logs. */
export function handleModLogStats(
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
  const authError = requireEventWorkerAuth(request, config);
  if (authError !== null) {
    return rejectedUpgrade(
      request,
      authError.status === 401 ? 'unauthorized' : 'server-authentication-unavailable',
      authError.status === 401
        ? 'Event Worker authentication failed.'
        : 'Server authentication is not configured.',
    );
  }
  const { response, socket } = Deno.upgradeWebSocket(request);
  hub.openLogStats(socket);
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
  usedBytes: bigint,
  maxBytes: bigint,
  logTokens: LogTokenBucketSnapshot | undefined,
): Internal.ExecutionStatsTick {
  const { logTokens: _modLogTokens, logTokensAvailable: _modLogTokensAvailable, ...modPlayer } =
    player;
  return create(Internal.ExecutionStatsTickSchema, {
    sampledAtUnixMillis: report.sampledAtUnixMillis,
    tickSequence: report.tickSequence,
    players: [create(Internal.ExecutionStatsTick_PlayerStatsSchema, {
      ...modPlayer,
      usedBytes,
      maxBytes,
      logTokensAvailable: logTokens !== undefined,
      ...(logTokens === undefined
        ? {}
        : { logTokens: create(Internal.LogTokenBucketSchema, logTokens) }),
    })],
  });
}

export function memoryUsageByPlayer(
  memoryStats: MemoryStats,
  playerIds: Iterable<string>,
): Map<string, bigint> {
  const usage = new Map<string, bigint>();
  const playerPrefixes = [...playerIds].map((playerId) =>
    [
      playerId,
      playerWorkerServicePathPrefix(playerId),
    ] as const
  );
  const workers = memoryStats instanceof Map ? memoryStats.values() : Object.values(memoryStats);
  for (const worker of workers) {
    const usedHeapSize = validByteCount(worker.stats?.usedHeapSize);
    const externalMemory = validByteCount(worker.stats?.externalMemory);
    if (usedHeapSize === undefined || externalMemory === undefined) continue;

    for (const [playerId, prefix] of playerPrefixes) {
      if (!worker.servicePath.startsWith(prefix)) continue;
      usage.set(playerId, (usage.get(playerId) ?? 0n) + usedHeapSize + externalMemory);
      break;
    }
  }
  return usage;
}

function validByteCount(value: number | undefined): bigint | undefined {
  return value === undefined || !Number.isSafeInteger(value) || value < 0
    ? undefined
    : BigInt(value);
}

function validLogTokenBucket(
  value: LogTokenBucketSnapshot | undefined,
): value is LogTokenBucketSnapshot {
  return value !== undefined && Number.isFinite(value.available) && value.available >= 0 &&
    Number.isInteger(value.capacity) && value.capacity > 0 && value.available <= value.capacity;
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
  if (
    player.maxRunningExecutions <= 0 || player.runningExecutions > player.maxRunningExecutions ||
    player.maxTotalExecutionStateBytes <= 0n || player.maxBurstChatTokens <= 0 ||
    player.maxSlowChatTokens <= 0 ||
    player.burstChatTokens > player.maxBurstChatTokens ||
    player.slowChatTokens > player.maxSlowChatTokens
  ) {
    throw new Error('chat token counts exceed their configured capacities');
  }
  if (player.logTokens !== undefined || player.logTokensAvailable) {
    throw new Error('log token bucket availability must be supplied by the Event Worker');
  }
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
    const key = cobbleputerStateKey(metric.cobbleputer);
    if (!validLifecycleState(metric.state) || states.has(key)) {
      throw new Error('Cobbleputer states must have unique valid Cobbleputers');
    }
    states.add(key);
  }
}

function requireEventWorkerAuth(request: Request, config: AppConfig): Response | null {
  const bearerError = requireModAuth(request, config);
  if (bearerError === null || config.apiKey === undefined || config.apiKey === '') {
    return bearerError;
  }
  const protocols = request.headers.get('sec-websocket-protocol');
  const expected = `cobbleware.bearer.${base64Url(config.apiKey)}`;
  return protocols?.split(',').some((protocol) => constantTimeEqual(protocol.trim(), expected))
    ? null
    : bearerError;
}

function base64Url(value: string): string {
  const bytes = new TextEncoder().encode(value);
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
}

function cobbleputerStateKey(
  value: Internal.ExecutionStatsTick_CobbleputerRef | undefined,
): string {
  if (value?.cobbleputerId === undefined) {
    throw new Error('Cobbleputer state must contain a Cobbleputer id');
  }
  return uuidString(value.cobbleputerId);
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
