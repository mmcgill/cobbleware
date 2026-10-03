import { fromBinary, isFieldSet } from '@bufbuild/protobuf';
import * as Internal from '@cobbleware/core/internal';
import type { AppConfig } from '../config.ts';
import { requireModAuth, requireSameOrigin } from '../auth/middleware.ts';
import { constantTimeEqual } from '../auth/crypto.ts';
import type { LogTokenBucketSnapshot } from '../stats/handlers.ts';

const EVENT_WORKER_PROTOCOL_PREFIX = 'cobbleware.bearer.';

export type RunningExecutionsSnapshot = {
  used: number;
  limit: number;
  totalExecutionStateLimit: bigint;
  updatedAt: string;
};

export type LogTokenBucketRecorder = (
  playerId: string,
  snapshot: LogTokenBucketSnapshot,
) => void;

/** Routes non-durable execution-log frames between authenticated producers and players. */
export class ExecutionLogHub {
  private readonly playerStreams = new Map<string, WebSocket>();
  private readonly producerStreams = new Set<WebSocket>();
  private readonly runningExecutionLimits = new Map<string, RunningExecutionsSnapshot>();

  constructor(private readonly recordLogTokens: LogTokenBucketRecorder = () => {}) {}

  runningExecutions(playerId: string): RunningExecutionsSnapshot | undefined {
    const snapshot = this.runningExecutionLimits.get(playerId);
    return snapshot === undefined ? undefined : { ...snapshot };
  }

  /** Records a transition's current running-execution limits before forwarding the display log. */
  recordExecutionTransition(
    playerId: string,
    transition: Internal.ExecutionTransitionLogEntry,
  ): void {
    if (
      !isFieldSet(transition, Internal.ExecutionTransitionLogEntrySchema.field.executionId) ||
      transition.executionId === undefined ||
      !isExecutionTransition(transition.transition) ||
      !Number.isInteger(transition.executionCount) ||
      !Number.isInteger(transition.executionLimit) ||
      transition.totalExecutionStateLimit <= 0n ||
      transition.executionCount < 0 ||
      transition.executionLimit <= 0 ||
      transition.executionCount > transition.executionLimit
    ) {
      throw new Error('execution transition is invalid');
    }
    this.runningExecutionLimits.set(playerId, {
      used: transition.executionCount,
      limit: transition.executionLimit,
      totalExecutionStateLimit: transition.totalExecutionStateLimit,
      updatedAt: new Date().toISOString(),
    });
  }

  openPlayer(playerId: string, socket: WebSocket): void {
    if (this.playerStreams.has(playerId)) {
      socket.onopen = () =>
        rejectSocket(
          socket,
          'execution-logs-stream-already-open',
          'Execution logs are already open in another browser window.',
        );
      return;
    }

    this.playerStreams.set(playerId, socket);
    socket.onmessage = () =>
      rejectSocket(
        socket,
        'execution-logs-stream-read-only',
        'Execution log streams are read-only.',
      );
    socket.onclose = () => this.releasePlayer(playerId, socket);
    socket.onerror = () => this.releasePlayer(playerId, socket);
  }

  openProducer(socket: WebSocket): void {
    this.producerStreams.add(socket);
    socket.binaryType = 'arraybuffer';
    socket.onmessage = (event) => {
      if (!(event.data instanceof ArrayBuffer)) {
        rejectSocket(
          socket,
          'invalid-execution-log-frame',
          'Execution logs must use binary frames.',
        );
        return;
      }
      this.receive(socket, event.data);
    };
    socket.onclose = () => this.releaseProducer(socket);
    socket.onerror = () => this.releaseProducer(socket);
  }

  closePlayer(playerId: string): void {
    const socket = this.playerStreams.get(playerId);
    if (socket === undefined) return;
    this.playerStreams.delete(playerId);
    try {
      socket.close(1008, 'session invalidated');
    } catch {
      // The closed socket has already released its stream slot.
    }
  }

  private receive(producer: WebSocket, payload: ArrayBuffer): void {
    let playerId: string;
    try {
      const entry = fromBinary(Internal.ExecutionLogEntrySchema, new Uint8Array(payload));
      if (
        !isFieldSet(entry, Internal.ExecutionLogEntrySchema.field.playerId) ||
        entry.playerId === undefined || entry.inner.case === undefined
      ) {
        throw new Error('execution log entry must contain a player id and entry');
      }
      playerId = uuidString(entry.playerId);
      if (entry.inner.case === 'transition') {
        this.recordExecutionTransition(playerId, entry.inner.value);
      } else if (entry.inner.case === 'poolTransition') {
        validateExecutionPoolTransition(entry.inner.value);
      } else if (entry.inner.case === 'logTokens') {
        validateLogTokenBucket(entry.inner.value);
        this.recordLogTokens(playerId, entry.inner.value);
        return;
      }
    } catch (error) {
      rejectSocket(
        producer,
        'invalid-execution-log-frame',
        describeError(error, 'Invalid execution log entry.'),
      );
      return;
    }

    const recipient = this.playerStreams.get(playerId);
    if (recipient === undefined || recipient.readyState !== WebSocket.OPEN) return;
    try {
      recipient.send(payload);
    } catch {
      this.releasePlayer(playerId, recipient);
    }
  }

  private releasePlayer(playerId: string, socket: WebSocket): void {
    if (this.playerStreams.get(playerId) === socket) this.playerStreams.delete(playerId);
  }

  private releaseProducer(socket: WebSocket): void {
    this.producerStreams.delete(socket);
  }
}

export function handleModExecutionLogs(
  request: Request,
  config: AppConfig,
  hub: ExecutionLogHub,
): Response {
  if (request.method !== 'GET') {
    return new Response(null, { status: 405, headers: { allow: 'GET' } });
  }
  if (!isWebSocketUpgrade(request)) {
    return new Response(null, { status: 426, headers: { upgrade: 'websocket' } });
  }
  const authError = requireLogProducerAuth(request, config);
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
  hub.openProducer(socket);
  return response;
}

export function handlePlayerExecutionLogs(
  request: Request,
  playerId: string | undefined,
  hub: ExecutionLogHub,
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
  hub.openPlayer(playerId!, socket);
  return response;
}

function rejectedUpgrade(request: Request, code: string, error: string): Response {
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

function uuidString(value: { high: bigint; low: bigint }): string {
  const hex = `${value.high.toString(16).padStart(16, '0')}${
    value.low.toString(16).padStart(16, '0')
  }`;
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${
    hex.slice(20)
  }`;
}

function isExecutionTransition(value: Internal.ExecutionTransition): boolean {
  return value === Internal.ExecutionTransition.STARTED ||
    value === Internal.ExecutionTransition.STOPPED ||
    value === Internal.ExecutionTransition.RESUMED ||
    value === Internal.ExecutionTransition.CRASHED;
}

function validateExecutionPoolTransition(
  transition: Internal.ExecutionPoolTransitionLogEntry,
): void {
  if (!isExecutionPoolTransition(transition.transition)) {
    throw new Error('execution-pool transition is invalid');
  }
}

function validateLogTokenBucket(value: Internal.LogTokenBucket): void {
  if (
    !Number.isFinite(value.available) || value.available < 0 ||
    !Number.isInteger(value.capacity) || value.capacity <= 0 || value.available > value.capacity
  ) {
    throw new Error('log token bucket is invalid');
  }
}

function isExecutionPoolTransition(value: Internal.ExecutionPoolTransition): boolean {
  return value === Internal.ExecutionPoolTransition.GUARANTEED ||
    value === Internal.ExecutionPoolTransition.BESTEFFORT ||
    value === Internal.ExecutionPoolTransition.WAITING ||
    value === Internal.ExecutionPoolTransition.NONE;
}

function isWebSocketUpgrade(request: Request): boolean {
  return request.headers.get('upgrade')?.toLowerCase() === 'websocket';
}

function describeError(error: unknown, fallback: string): string {
  return error instanceof Error && error.message !== '' ? error.message : fallback;
}

function requireLogProducerAuth(request: Request, config: AppConfig): Response | null {
  const bearerError = requireModAuth(request, config);
  if (bearerError === null || config.apiKey === undefined || config.apiKey === '') {
    return bearerError;
  }
  if (hasEventWorkerProtocol(request, config.apiKey)) return null;
  return bearerError;
}

function hasEventWorkerProtocol(request: Request, apiKey: string): boolean {
  const protocols = request.headers.get('sec-websocket-protocol');
  if (protocols === null) return false;
  const expected = `${EVENT_WORKER_PROTOCOL_PREFIX}${base64Url(apiKey)}`;
  return protocols.split(',').some((protocol) => constantTimeEqual(protocol.trim(), expected));
}

function base64Url(value: string): string {
  const bytes = new TextEncoder().encode(value);
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
}
