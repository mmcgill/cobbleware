import { handlePlayerLimits } from '../src/limits/handlers.ts';

Deno.test('Player limits endpoint returns the cached player-statistics snapshot', async () => {
  const response = handlePlayerLimits(
    new Request('http://localhost/playerapi/v1/limits'),
    'player-a',
    stats(42n),
  );

  assertEquals(response.status, 200, 'cached limits should be available');
  const body = await response.json();
  assertEquals(body, {
    runningExecutions: { value: 2, limit: 10 },
    totalExecutionStateBytes: {
      value: 42,
      limit: 1024,
    },
    burstChatTokens: { value: 8, limit: 10 },
    slowChatTokens: { value: 72, limit: 100 },
    logTokens: { value: 7.5, limit: 100 },
    updatedAt: body.updatedAt,
  }, 'limits response should preserve the authoritative snapshot');
});

Deno.test('Player limits endpoint reports unavailable until player statistics arrive', () => {
  const unavailable = handlePlayerLimits(
    new Request('http://localhost/playerapi/v1/limits'),
    'player-a',
    unavailableStats(),
  );
  assertEquals(unavailable.status, 503, 'missing snapshot should not be represented as zero usage');

  const method = handlePlayerLimits(
    new Request('http://localhost/playerapi/v1/limits', { method: 'POST' }),
    'player-a',
    unavailableStats(),
  );
  assertEquals(method.status, 405, 'limits only supports GET');
  assertEquals(method.headers.get('allow'), 'GET', 'limits advertises GET');
});

Deno.test('Player limits endpoint omits unavailable program-log token information', async () => {
  const response = handlePlayerLimits(
    new Request('http://localhost/playerapi/v1/limits'),
    'player-a',
    stats(42n, false),
  );

  assertEquals(
    response.status,
    200,
    'other available limits should not depend on log-token telemetry',
  );
  const body = await response.json() as Record<string, unknown>;
  assertEquals('logTokens' in body, false, 'unavailable log-token information should be omitted');
  assertEquals(
    body.runningExecutions,
    { value: 2, limit: 10 },
    'running-execution limit should remain available',
  );
  assertEquals(
    body.totalExecutionStateBytes,
    { value: 42, limit: 1024 },
    'execution-state limit should remain available',
  );
});

function stats(used: bigint, includeLogTokens = true) {
  return {
    playerLimits: () => ({
      runningExecutions: { value: 2, limit: 10 },
      executionStateBytes: used,
      executionStateLimit: 1024n,
      burstChatTokens: { value: 8, limit: 10 },
      slowChatTokens: { value: 72, limit: 100 },
      ...(includeLogTokens ? { logTokens: { value: 7.5, limit: 100 } } : {}),
      updatedAt: '2026-10-04T12:00:00.000Z',
    }),
  } as never;
}

function unavailableStats() {
  return { playerLimits: () => undefined } as never;
}

function assertEquals<T>(actual: T, expected: T, message: string): void {
  const actualJson = JSON.stringify(actual);
  const expectedJson = JSON.stringify(expected);
  if (actualJson !== expectedJson) {
    throw new Error(`${message}: expected ${expectedJson}, got ${actualJson}`);
  }
}
