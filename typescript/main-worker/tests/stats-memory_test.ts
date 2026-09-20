import { workerServicePath } from '../src/programs/service-path.ts';
import { memoryUsageByPlayer } from '../src/stats/handlers.ts';

const PLAYER = 'd2a99799-250c-4eff-a5ed-dc421fcd189c';
const OTHER_PLAYER = '123e4567-e89b-12d3-a456-426614174000';

Deno.test('Execution memory stats sum only the authenticated player workers', () => {
  const usage = memoryUsageByPlayer({
    first: {
      servicePath: workerServicePath(PLAYER, 'FIRST'),
      stats: { usedHeapSize: 10, externalMemory: 3 },
    },
    second: {
      servicePath: workerServicePath(PLAYER, 'SECOND'),
      stats: { usedHeapSize: 20, externalMemory: 7 },
    },
    other: {
      servicePath: workerServicePath(OTHER_PLAYER, 'OTHER'),
      stats: { usedHeapSize: 100, externalMemory: 50 },
    },
    unrelated: {
      servicePath: 'not-a-cobbleware-worker',
      stats: { usedHeapSize: 1000, externalMemory: 500 },
    },
  }, [PLAYER, OTHER_PLAYER]);

  assertEquals(usage.get(PLAYER), 40n, 'player memory usage was not summed');
  assertEquals(usage.get(OTHER_PLAYER), 150n, 'other player memory usage changed');
});

Deno.test('Execution memory stats ignore unavailable and invalid worker statistics', () => {
  const usage = memoryUsageByPlayer({
    unavailable: { servicePath: workerServicePath(PLAYER, 'UNAVAILABLE') },
    invalid: {
      servicePath: workerServicePath(PLAYER, 'INVALID'),
      stats: { usedHeapSize: -1, externalMemory: 1 },
    },
  }, [PLAYER]);

  assertEquals(usage.get(PLAYER), undefined, 'invalid worker statistics should be ignored');
});

Deno.test('Execution memory stats support Edge Runtime map responses', () => {
  const usage = memoryUsageByPlayer(
    new Map([
      ['worker', {
        servicePath: workerServicePath(PLAYER, 'MAP'),
        stats: { usedHeapSize: 1, externalMemory: 2 },
      }],
    ]),
    [PLAYER],
  );

  assertEquals(usage.get(PLAYER), 3n, 'map-shaped runtime statistics were not read');
});

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) throw new Error(`${message}: expected ${expected}, got ${actual}`);
}
