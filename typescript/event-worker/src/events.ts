import { create } from '@cobbleware/core';
import { Common } from '@cobbleware/core';
import * as Internal from '@cobbleware/core/internal';
import type { LogTokenBucketSnapshot } from './limiter.ts';

export const MAX_LOG_MESSAGE_BYTES = 10 * 1024;

export type EventManagerEvent = {
  event_type?: string;
  event?: unknown;
  metadata?: { service_path?: string };
};

export type TranslatedLogEvent = {
  playerId: string;
  entry: Internal.ExecutionLogEntry;
  isProgramLog: boolean;
};

/** Creates internal Event Worker telemetry; the Main Worker does not display it as a log entry. */
export function logStats(
  playerId: string,
  snapshot: LogTokenBucketSnapshot,
): Internal.LogStats | undefined {
  const playerUuid = uuidFromString(playerId);
  if (playerUuid === undefined) return undefined;
  return create(Internal.LogStatsSchema, {
    playerId: playerUuid,
    logTokens: create(Internal.LogTokenBucketSchema, snapshot),
  });
}

export function translateEvent(data: EventManagerEvent): TranslatedLogEvent | undefined {
  const playerId = playerIdFromServicePath(data.metadata?.service_path);
  if (playerId === undefined) return undefined;

  const event = record(data.event);
  if (event === undefined) return undefined;
  const playerUuid = uuidFromString(playerId);
  if (playerUuid === undefined) return undefined;

  switch (data.event_type) {
    case 'BootFailure':
      return translated(playerId, playerUuid, {
        case: 'exception',
        value: create(Internal.ExceptionLogEntrySchema, { msg: string(event.msg) }),
      });
    case 'UncaughtException':
      return translated(playerId, playerUuid, {
        case: 'exception',
        value: create(Internal.ExceptionLogEntrySchema, {
          msg: string(event.exception),
          cpuTimeUsed: uint64(event.cpu_time_used),
        }),
      });
    case 'Shutdown': {
      const reason = string(event.reason);
      if (reason !== 'CPUTime' && reason !== 'Memory') return undefined;
      const memory = record(event.memory_used);
      return translated(playerId, playerUuid, {
        case: 'limited',
        value: create(Internal.LimitExceededLogEntrySchema, {
          reason,
          cpuTimeUsed: uint64(event.cpu_time_used),
          totalMemUsed: uint64(memory?.total),
        }),
      });
    }
    case 'Log': {
      const level = logLevel(event.level);
      if (level === undefined) return undefined;
      return translated(playerId, playerUuid, {
        case: 'log',
        value: create(Internal.ProgramLogEntrySchema, {
          level,
          msg: truncateUtf8(string(event.msg)),
        }),
      }, true);
    }
    default:
      return undefined;
  }
}

export function playerIdFromServicePath(servicePath: string | undefined): string | undefined {
  if (servicePath === undefined) return undefined;
  const match = /^cobbleware-(.+)-[A-Z2-7]{52}$/.exec(servicePath);
  if (match === null) return undefined;
  try {
    const playerId = decodeURIComponent(match[1]).toLowerCase();
    return uuidFromString(playerId) === undefined ? undefined : playerId;
  } catch {
    return undefined;
  }
}

export function truncateUtf8(message: string): string {
  const bytes = new TextEncoder().encode(message);
  if (bytes.byteLength <= MAX_LOG_MESSAGE_BYTES) return message;

  let end = MAX_LOG_MESSAGE_BYTES;
  while (end > 0 && (bytes[end] & 0xc0) === 0x80) end -= 1;
  return new TextDecoder().decode(bytes.slice(0, end));
}

function translated(
  playerId: string,
  playerUuid: Common.Uuid,
  inner: Internal.ExecutionLogEntry['inner'],
  isProgramLog = false,
): TranslatedLogEvent {
  return {
    playerId,
    entry: create(Internal.ExecutionLogEntrySchema, { playerId: playerUuid, inner }),
    isProgramLog,
  };
}

function uuidFromString(value: string): Common.Uuid | undefined {
  const match = /^([0-9a-f]{8})-([0-9a-f]{4})-([0-9a-f]{4})-([0-9a-f]{4})-([0-9a-f]{12})$/.exec(
    value,
  );
  if (match === null) return undefined;
  const hex = match.slice(1).join('');
  return create(Common.UuidSchema, {
    high: BigInt(`0x${hex.slice(0, 16)}`),
    low: BigInt(`0x${hex.slice(16)}`),
  });
}

function logLevel(value: unknown): Internal.LogLevel | undefined {
  switch (value) {
    case 'Debug':
      return Internal.LogLevel.DEBUG;
    case 'Info':
      return Internal.LogLevel.INFO;
    case 'Warning':
      return Internal.LogLevel.WARN;
    case 'Error':
      return Internal.LogLevel.ERROR;
    default:
      return undefined;
  }
}

function record(value: unknown): Record<string, unknown> | undefined {
  return typeof value === 'object' && value !== null ? value as Record<string, unknown> : undefined;
}

function string(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function uint64(value: unknown): bigint {
  if (typeof value === 'bigint') return value >= 0n ? value : 0n;
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
    ? BigInt(value)
    : 0n;
}
