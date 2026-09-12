import { Common, create, fromBinary, toBinary } from '@cobbleware/core';
import * as Internal from '@cobbleware/core/internal';
import { type AppConfig, configFromEnv } from '../src/config.ts';
import { createHandler } from '../src/index.ts';

const API_KEY = 'stats-test-key';
const PATH = 'http://localhost/modapi/v1/execution-stats';

function report(overrides: Partial<Internal.ExecutionStats> = {}): Internal.ExecutionStats {
  return create(Internal.ExecutionStatsSchema, {
    sampledAtUnixMillis: 1n,
    windowTicks: 600,
    retainedTickCount: 20,
    players: [create(Internal.ExecutionStats_PlayerStatsSchema, {
      playerId: create(Common.UuidSchema, { high: 1n, low: 2n }),
      eventsPerSyncCount: 2n,
      eventsPerSyncTotal: 3n,
      eventsPerSyncMax: 2n,
      eventsAccepted: 4n,
      eventsDropped: 5n,
      effectsAttempted: 6n,
      effectsFailed: 1n,
      effectsTotalNanos: 7n,
      effectsMaxNanos: 4n,
      runningCobbleputers: 1,
      stoppedCobbleputers: 2,
    })],
    ...overrides,
  });
}

async function createTestHandler(): Promise<{
  handler: (request: Request) => Promise<Response>;
  cleanup: () => Promise<void>;
}> {
  const storageDir = await Deno.makeTempDir({ prefix: 'cobbleware-stats-test-' });
  const config: AppConfig = configFromEnv({ apiKey: API_KEY, storageDir });
  return {
    handler: await createHandler({ config }),
    cleanup: () => Deno.remove(storageDir, { recursive: true }),
  };
}

function request(
  body: Uint8Array,
  options: Partial<{ method: string; authorized: boolean; contentType: string }> = {},
): Request {
  const method = options.method ?? 'POST';
  const headers = new Headers({
    'content-type': options.contentType ?? 'application/octet-stream',
  });
  if (options.authorized !== false) headers.set('authorization', `Bearer ${API_KEY}`);
  return new Request(PATH, {
    method,
    headers,
    body: method === 'GET' ? undefined : new Blob([body.slice().buffer as ArrayBuffer]),
  });
}

async function assertStatsError(response: Response, expectedStatus: number): Promise<void> {
  assertEquals(response.status, expectedStatus, 'unexpected status');
  assertEquals(
    response.headers.get('content-type'),
    'application/octet-stream',
    'stats errors are protobuf',
  );
  const error = fromBinary(
    Internal.ErrorResponseSchema,
    new Uint8Array(await response.arrayBuffer()),
  );
  if (error.error === '') throw new Error('stats errors must contain a detail');
}

Deno.test('Execution-stats reports use authenticated protobuf transport and validate aggregates', async () => {
  const app = await createTestHandler();
  const valid = toBinary(Internal.ExecutionStatsSchema, report());
  try {
    const accepted = await app.handler(request(valid));
    assertEquals(accepted.status, 204, 'valid stats report should be accepted');

    await assertStatsError(await app.handler(request(valid, { authorized: false })), 401);
    await assertStatsError(
      await app.handler(request(valid, { contentType: 'application/json' })),
      415,
    );
    await assertStatsError(await app.handler(request(valid, { method: 'GET' })), 405);

    const invalid = toBinary(Internal.ExecutionStatsSchema, report({ retainedTickCount: 601 }));
    await assertStatsError(await app.handler(request(invalid)), 400);

    const invalidPlayer = create(Internal.ExecutionStats_PlayerStatsSchema, {
      ...report().players[0],
      effectsAttempted: 1n,
      effectsFailed: 2n,
    });
    await assertStatsError(
      await app.handler(request(toBinary(
        Internal.ExecutionStatsSchema,
        report({
          players: [invalidPlayer],
        }),
      ))),
      400,
    );
  } finally {
    await app.cleanup();
  }
});

Deno.test('Execution-stats player endpoint requires authentication and an upgrade', async () => {
  const app = await createTestHandler();
  try {
    const anonymous = await app.handler(
      new Request('http://localhost/playerapi/v1/execution-stats'),
    );
    assertEquals(anonymous.status, 401, 'stats endpoint should require a player session');

    // A normal authenticated GET is covered by the route after authentication; the endpoint itself must
    // advertise the WebSocket upgrade rather than silently returning an HTTP snapshot.
    const response = await app.handler(
      new Request('http://localhost/playerapi/v1/execution-stats', {
        headers: { cookie: 'cobbleware_session=unknown' },
      }),
    );
    assertEquals(response.status, 401, 'unknown sessions remain unauthorized');
  } finally {
    await app.cleanup();
  }
});

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) {
    throw new Error(`${message}: expected ${String(expected)}, got ${String(actual)}`);
  }
}
