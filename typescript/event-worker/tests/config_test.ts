import {
  configFromEnv,
  DEFAULT_LOG_RATE_PER_SECOND,
  DEFAULT_MAIN_WORKER_URL,
  logStatsUrlFromBase,
  logsUrlFromBase,
} from '../src/config.ts';

Deno.test('Event Worker derives its WebSocket endpoint from the Main Worker base URL', () => {
  assertEquals(
    logsUrlFromBase('https://example.test/cobbleware'),
    'wss://example.test/cobbleware/modapi/v1/logs',
    'path-prefixed HTTPS URL was not preserved',
  );
  assertEquals(
    logStatsUrlFromBase('https://example.test/cobbleware'),
    'wss://example.test/cobbleware/modapi/v1/log-stats',
    'log-stats path-prefixed HTTPS URL was not preserved',
  );
  assertEquals(
    logsUrlFromBase(DEFAULT_MAIN_WORKER_URL),
    'ws://127.0.0.1:9000/modapi/v1/logs',
    'default URL changed',
  );
});

Deno.test('Event Worker validates its required key and log rate', () => {
  assertThrows(
    () => configFromEnv({ apiKey: '', logsUrl: 'ws://localhost/modapi/v1/logs' }),
    'missing API key was accepted',
  );
  assertThrows(
    () => configFromEnv({ apiKey: 'key', logsUrl: 'ws://localhost/logs', logRatePerSecond: -1 }),
    'negative log rate was accepted',
  );
  const config = configFromEnv({ apiKey: 'key', logsUrl: 'ws://localhost/logs' });
  assertEquals(config.logRatePerSecond, DEFAULT_LOG_RATE_PER_SECOND, 'default rate changed');
});

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) throw new Error(`${message}: expected ${expected}, got ${actual}`);
}

function assertThrows(action: () => unknown, message: string): void {
  try {
    action();
  } catch {
    return;
  }
  throw new Error(message);
}
