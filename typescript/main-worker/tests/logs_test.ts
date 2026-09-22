import { type AppConfig, configFromEnv } from '../src/config.ts';
import { createHandler } from '../src/index.ts';

const API_KEY = 'logs-test-key';

Deno.test('Execution-log producer endpoint requires an authenticated WebSocket GET', async () => {
  const app = await createTestHandler();
  try {
    const post = await app.handler(
      new Request('http://localhost/modapi/v1/logs', {
        method: 'POST',
        headers: { authorization: `Bearer ${API_KEY}` },
      }),
    );
    assertEquals(post.status, 405, 'logs POST must be rejected');
    assertEquals(post.headers.get('allow'), 'GET', 'logs endpoint must advertise GET');

    const get = await app.handler(
      new Request('http://localhost/modapi/v1/logs', {
        headers: { authorization: `Bearer ${API_KEY}` },
      }),
    );
    assertEquals(get.status, 426, 'normal GET must require a WebSocket upgrade');
    assertEquals(
      get.headers.get('upgrade'),
      'websocket',
      'logs endpoint must advertise websocket upgrade',
    );
  } finally {
    await app.cleanup();
  }
});

Deno.test('Execution-log player endpoint requires a Player session', async () => {
  const app = await createTestHandler();
  try {
    const response = await app.handler(new Request('http://localhost/playerapi/v1/logs'));
    assertEquals(response.status, 401, 'logs endpoint should require a player session');
  } finally {
    await app.cleanup();
  }
});

async function createTestHandler(): Promise<{
  handler: (request: Request) => Promise<Response>;
  cleanup: () => Promise<void>;
}> {
  const storageDir = await Deno.makeTempDir({ prefix: 'cobbleware-logs-test-' });
  const config: AppConfig = configFromEnv({ apiKey: API_KEY, storageDir });
  return {
    handler: await createHandler({ config }),
    cleanup: () => Deno.remove(storageDir, { recursive: true }),
  };
}

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) throw new Error(`${message}: expected ${expected}, got ${actual}`);
}
