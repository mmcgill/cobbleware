import { handlePlayerLimits } from '../src/limits/handlers.ts';
import { ExecutionLogHub } from '../src/logs/handlers.ts';
import { Common, create } from '@cobbleware/core';
import * as Internal from '@cobbleware/core/internal';

Deno.test('Player limits endpoint returns the cached running-execution snapshot', async () => {
  const logs = new ExecutionLogHub();
  logs.recordExecutionTransition('player-a', transition(2, 10));
  const response = handlePlayerLimits(
    new Request('http://localhost/playerapi/v1/limits'),
    'player-a',
    logs,
  );

  assertEquals(response.status, 200, 'cached limits should be available');
  const body = await response.json();
  assertEquals(body, {
    runningExecutions: { used: 2, limit: 10 },
    updatedAt: body.updatedAt,
  }, 'limits response should preserve the authoritative snapshot');
});

Deno.test('Player limits endpoint reports unavailable until a snapshot arrives', async () => {
  const unavailable = handlePlayerLimits(
    new Request('http://localhost/playerapi/v1/limits'),
    'player-a',
    new ExecutionLogHub(),
  );
  assertEquals(unavailable.status, 503, 'missing snapshot should not be represented as zero usage');

  const method = handlePlayerLimits(
    new Request('http://localhost/playerapi/v1/limits', { method: 'POST' }),
    'player-a',
    new ExecutionLogHub(),
  );
  assertEquals(method.status, 405, 'limits only supports GET');
  assertEquals(method.headers.get('allow'), 'GET', 'limits advertises GET');
});

Deno.test('Execution transitions replace the cached running-execution limits', () => {
  const logs = new ExecutionLogHub();
  logs.recordExecutionTransition('player-a', transition(2, 10));
  logs.recordExecutionTransition('player-a', transition(1, 12));
  assertEquals(
    logs.runningExecutions('player-a')?.used,
    1,
    'the later transition should replace current usage',
  );
  assertEquals(
    logs.runningExecutions('player-a')?.limit,
    12,
    'the later transition should replace the configured limit',
  );
});

function transition(
  executionCount: number,
  executionLimit: number,
): Internal.ExecutionTransitionLogEntry {
  return create(Internal.ExecutionTransitionLogEntrySchema, {
    executionId: create(Common.UuidSchema, { high: 1n, low: 2n }),
    transition: Internal.ExecutionTransition.STARTED,
    executionCount,
    executionLimit,
  });
}

function assertEquals<T>(actual: T, expected: T, message: string): void {
  const actualJson = JSON.stringify(actual);
  const expectedJson = JSON.stringify(expected);
  if (actualJson !== expectedJson) {
    throw new Error(`${message}: expected ${expectedJson}, got ${actualJson}`);
  }
}
