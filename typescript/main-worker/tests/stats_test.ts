import { type AppConfig, configFromEnv } from '../src/config.ts';
import { createHandler } from '../src/index.ts';

const API_KEY = 'stats-test-key';

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

Deno.test('Execution-stats Mod endpoint requires a WebSocket GET', async () => {
  const app = await createTestHandler();
  try {
    const post = await app.handler(
      new Request('http://localhost/modapi/v1/execution-stats', {
        method: 'POST',
        headers: { authorization: `Bearer ${API_KEY}` },
      }),
    );
    assertEquals(post.status, 405, 'stats POST must be removed');
    assertEquals(post.headers.get('allow'), 'GET', 'stats endpoint must advertise GET');

    const get = await app.handler(
      new Request('http://localhost/modapi/v1/execution-stats', {
        headers: { authorization: `Bearer ${API_KEY}` },
      }),
    );
    assertEquals(get.status, 426, 'normal GET must require a WebSocket upgrade');
    assertEquals(
      get.headers.get('upgrade'),
      'websocket',
      'stats endpoint must advertise websocket upgrade',
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
  } finally {
    await app.cleanup();
  }
});

Deno.test('Log-stats endpoint requires a WebSocket GET', async () => {
  const app = await createTestHandler();
  try {
    const post = await app.handler(
      new Request('http://localhost/modapi/v1/log-stats', { method: 'POST' }),
    );
    assertEquals(post.status, 405, 'log stats POST must be rejected');
    assertEquals(post.headers.get('allow'), 'GET', 'log stats must advertise GET');

    const get = await app.handler(new Request('http://localhost/modapi/v1/log-stats'));
    assertEquals(get.status, 426, 'normal GET must require a WebSocket upgrade');
  } finally {
    await app.cleanup();
  }
});

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) {
    throw new Error(`${message}: expected ${String(expected)}, got ${String(actual)}`);
  }
}
