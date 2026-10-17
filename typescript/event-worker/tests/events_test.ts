import { LogLevel } from '@cobbleware/core/internal';
import {
  logStats,
  MAX_LOG_MESSAGE_BYTES,
  playerIdFromServicePath,
  translateEvent,
  truncateUtf8,
} from '../src/events.ts';

const PLAYER_ID = 'd2a99799-250c-4eff-a5ed-dc421fcd189c';
const SERVICE_PATH = `cobbleware-${PLAYER_ID}-${'A'.repeat(52)}`;

Deno.test('Event Worker translates supported Runtime events', () => {
  const bootFailure = translateEvent({
    event_type: 'BootFailure',
    event: { msg: 'boot failed' },
    metadata: { service_path: SERVICE_PATH },
  });
  assertEquals(bootFailure?.entry.inner.case, 'exception', 'boot failure was not an exception');
  if (bootFailure?.entry.inner.case !== 'exception') {
    throw new Error('wrong boot-failure entry case');
  }
  assertEquals(bootFailure.entry.inner.value.msg, 'boot failed', 'boot failure message changed');
  assertEquals(bootFailure.entry.inner.value.cpuTimeUsed, 0n, 'boot failure gained CPU time');

  const exception = translateEvent({
    event_type: 'UncaughtException',
    event: { exception: 'oops', cpu_time_used: 17 },
    metadata: { service_path: SERVICE_PATH },
  });
  assertEquals(exception?.entry.inner.case, 'exception', 'exception was dropped');
  if (exception?.entry.inner.case !== 'exception') throw new Error('wrong exception entry case');
  assertEquals(exception.entry.inner.value.cpuTimeUsed, 17n, 'exception CPU time changed');

  const limited = translateEvent({
    event_type: 'Shutdown',
    event: { reason: 'Memory', cpu_time_used: 19, memory_used: { total: 23 } },
    metadata: { service_path: SERVICE_PATH },
  });
  assertEquals(limited?.entry.inner.case, 'limited', 'memory shutdown was dropped');
  if (limited?.entry.inner.case !== 'limited') throw new Error('wrong shutdown entry case');
  assertEquals(limited.entry.inner.value.totalMemUsed, 23n, 'memory usage changed');

  const log = translateEvent({
    event_type: 'Log',
    event: { level: 'Warning', msg: 'careful' },
    metadata: { service_path: SERVICE_PATH },
  });
  if (log?.entry.inner.case !== 'log') throw new Error('log was not translated');
  assertEquals(log.entry.inner.value.level, LogLevel.WARN, 'warning level changed');
  assertEquals(log.isProgramLog, true, 'program log was not marked rate-limited');
});

Deno.test('Event Worker discards unattributable and irrelevant Runtime events', () => {
  assertEquals(
    translateEvent({ event_type: 'Boot', event: {}, metadata: { service_path: SERVICE_PATH } }),
    undefined,
    'boot event was forwarded',
  );
  assertEquals(
    translateEvent({
      event_type: 'Shutdown',
      event: { reason: 'WallClockTime' },
      metadata: { service_path: SERVICE_PATH },
    }),
    undefined,
    'unrelated shutdown was forwarded',
  );
  assertEquals(
    playerIdFromServicePath('main-worker'),
    undefined,
    'main worker path was attributed',
  );
  assertEquals(
    playerIdFromServicePath(`cobbleware-not-a-uuid-${'A'.repeat(52)}`),
    undefined,
    'invalid player id was attributed',
  );
});

Deno.test('Event Worker truncates Log messages at a UTF-8 boundary', () => {
  const message = `${'x'.repeat(MAX_LOG_MESSAGE_BYTES - 1)}😀`;
  const truncated = truncateUtf8(message);
  assertEquals(
    new TextEncoder().encode(truncated).byteLength,
    MAX_LOG_MESSAGE_BYTES - 1,
    'wrong byte cap',
  );
  assertEquals(truncated.endsWith('�'), false, 'truncation split a UTF-8 code point');
});

Deno.test('Event Worker creates dedicated log-token telemetry', () => {
  const stats = logStats(PLAYER_ID, { available: 99.5, capacity: 100 });
  assertEquals(stats?.logTokens?.available, 99.5, 'log-token allowance changed');
  assertEquals(stats?.logTokens?.capacity, 100, 'log-token capacity changed');
});

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) throw new Error(`${message}: expected ${expected}, got ${actual}`);
}
