import { type AppConfig, configFromEnv } from '../src/config.ts';
import { DurableAuthStore } from '../src/auth/store.ts';
import { createHandler } from '../src/index.ts';

const MOD_AUTH_PATH = 'http://localhost/modapi/v1/authentication';
const PLAYER_AUTH_PATH = 'http://localhost/playerapi/v1/authenticate';
const PROTECTED_API_PATH = 'http://localhost/playerapi/v1/unknown';
const PROTECTED_HTML_PATH = 'http://localhost/index.html';
const API_KEY = 'test-api-key';
const PLAYER_UUID = '123e4567-e89b-12d3-a456-426614174000';

type TestApp = {
  handler: (request: Request) => Promise<Response>;
  config: AppConfig;
  store: DurableAuthStore;
  cleanup: () => Promise<void>;
};

async function createTestApp(options: {
  clock?: () => number;
  pendingAuthCodeLifetimeMs?: number;
  sessionLifetimeMs?: number;
  tokenGenerator?: () => string;
} = {}): Promise<TestApp> {
  const storageDir = await Deno.makeTempDir({ prefix: 'cobbleware-auth-test-' });
  const config = configFromEnv({
    apiKey: API_KEY,
    storageDir,
    pendingAuthCodeLifetimeMs: options.pendingAuthCodeLifetimeMs,
    sessionLifetimeMs: options.sessionLifetimeMs,
  });
  const store = await DurableAuthStore.open(config, {
    clock: options.clock,
    tokenGenerator: options.tokenGenerator,
  });

  return {
    handler: await createHandler({ config, store }),
    config,
    store,
    cleanup: () => Deno.remove(storageDir, { recursive: true }),
  };
}

function jsonRequest(
  url: string,
  body: unknown,
  headers: Record<string, string> = {},
): Request {
  return new Request(url, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${API_KEY}`,
      'content-type': 'application/json',
      ...headers,
    },
    body: JSON.stringify(body),
  });
}

function authenticateMod(
  handler: TestApp['handler'],
  code = 'XAU1-98B1',
  playerUUID = PLAYER_UUID,
  playerProfileName = 'Alice',
): Promise<Response> {
  return handler(jsonRequest(MOD_AUTH_PATH, { code, playerUUID, playerProfileName }));
}

function authenticatePlayer(
  handler: TestApp['handler'],
  code = 'XAU1-98B1',
): Promise<Response> {
  return handler(jsonRequest(PLAYER_AUTH_PATH, { code }, {}));
}

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) {
    throw new Error(`${message}: expected ${String(expected)}, got ${String(actual)}`);
  }
}

async function assertErrorResponse(response: Response, status: number): Promise<void> {
  assertEquals(response.status, status, 'unexpected response status');
  const body = await response.json();
  if (typeof body.error !== 'string') {
    throw new Error(`expected an error property, got ${JSON.stringify(body)}`);
  }
}

Deno.test('Mod authentication binds a code and Player authentication consumes it once', async () => {
  const app = await createTestApp({ tokenGenerator: () => 'session-token' });
  try {
    const modResponse = await authenticateMod(app.handler);
    assertEquals(modResponse.status, 201, 'Mod authentication should succeed');
    assertEquals(await modResponse.text(), '', 'Mod authentication should have no body');

    const playerResponse = await authenticatePlayer(app.handler, 'xau1-98b1');
    assertEquals(playerResponse.status, 201, 'Player authentication should succeed');
    assertEquals(await playerResponse.text(), '', 'Player authentication should have no body');
    const setCookie = playerResponse.headers.get('set-cookie');
    if (
      setCookie !==
        'cobbleware_session=session-token; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000'
    ) {
      throw new Error(`unexpected Set-Cookie header: ${setCookie}`);
    }

    await Deno.stat(`${app.config.storageDir}/auth.json`);

    await assertErrorResponse(await authenticatePlayer(app.handler), 400);
  } finally {
    await app.cleanup();
  }
});

Deno.test('Mod authentication is idempotent for one player and rejects conflicts', async () => {
  const app = await createTestApp({ tokenGenerator: () => 'session-token' });
  try {
    assertEquals((await authenticateMod(app.handler)).status, 201, 'initial bind should succeed');
    assertEquals(
      (await authenticateMod(app.handler, 'XAU1-98B1', PLAYER_UUID, 'ChangedName')).status,
      201,
      'same-player retry should succeed',
    );
    await assertErrorResponse(
      await authenticateMod(app.handler, 'XAU1-98B1', '123e4567-e89b-12d3-a456-426614174001'),
      400,
    );

    const playerResponse = await authenticatePlayer(app.handler);
    assertEquals(playerResponse.status, 201, 'original binding should remain usable');
    const session = playerResponse.headers.get('set-cookie')?.split(';', 1)[0].split('=', 2)[1];
    assertEquals(session, 'session-token', 'expected deterministic session token');
  } finally {
    await app.cleanup();
  }
});

Deno.test('Mod authentication rejects missing or incorrect bearer credentials', async () => {
  const app = await createTestApp();
  try {
    const request = jsonRequest(MOD_AUTH_PATH, {
      code: 'XAU1-98B1',
      playerUUID: PLAYER_UUID,
      playerProfileName: 'Alice',
    }, { authorization: 'Basic wrong' });
    const response = await app.handler(request);
    await assertErrorResponse(response, 401);
    assertEquals(response.headers.get('www-authenticate'), 'Bearer', 'expected bearer challenge');
  } finally {
    await app.cleanup();
  }
});

Deno.test('Player and HTML protection use different unauthorized responses', async () => {
  const app = await createTestApp({ tokenGenerator: () => 'session-token' });
  try {
    const apiResponse = await app.handler(new Request(PROTECTED_API_PATH));
    await assertErrorResponse(apiResponse, 401);
    assertEquals(
      apiResponse.headers.get('www-authenticate'),
      'Cobbleware realm="player", location="/auth.html"',
      'expected player challenge',
    );

    const htmlResponse = await app.handler(new Request(PROTECTED_HTML_PATH));
    assertEquals(htmlResponse.status, 302, 'HTML should redirect to auth page');
    assertEquals(htmlResponse.headers.get('location'), '/auth.html', 'expected auth redirect');

    const publicAuthPage = await app.handler(new Request('http://localhost/auth.html'));
    assertEquals(publicAuthPage.status, 200, 'auth page route should be public');
    assertEquals(
      publicAuthPage.headers.get('content-type'),
      'text/html; charset=utf-8',
      'auth page should be HTML',
    );

    await authenticateMod(app.handler);
    const playerResponse = await authenticatePlayer(app.handler);
    const cookie = playerResponse.headers.get('set-cookie')?.split(';', 1)[0];
    if (cookie === undefined) {
      throw new Error('expected a session cookie');
    }
    const authenticatedApiResponse = await app.handler(
      new Request(PROTECTED_API_PATH, { headers: { cookie } }),
    );
    assertEquals(authenticatedApiResponse.status, 404, 'valid session should reach the route');

    const authenticatedIndex = await app.handler(
      new Request(PROTECTED_HTML_PATH, { headers: { cookie } }),
    );
    assertEquals(authenticatedIndex.status, 200, 'valid session should serve the index page');
  } finally {
    await app.cleanup();
  }
});

Deno.test('auth codes expire and sessions survive store reload until their deadline', async () => {
  let nowMs = 1_000;
  const app = await createTestApp({
    clock: () => nowMs,
    pendingAuthCodeLifetimeMs: 100,
    sessionLifetimeMs: 200,
    tokenGenerator: () => 'session-token',
  });
  try {
    await authenticateMod(app.handler);
    nowMs += 100;
    await assertErrorResponse(await authenticatePlayer(app.handler), 400);

    await authenticateMod(app.handler, 'ABCD-2345');
    const playerResponse = await authenticatePlayer(app.handler, 'ABCD-2345');
    assertEquals(playerResponse.status, 201, 'fresh code should succeed');

    const reloadedStore = await DurableAuthStore.open(app.config, {
      clock: () => nowMs,
    });
    const identity = await reloadedStore.findSession('session-token');
    assertEquals(identity?.playerUUID, PLAYER_UUID, 'session should survive store reload');

    nowMs += 200;
    assertEquals(
      await reloadedStore.findSession('session-token'),
      null,
      'expired session should be removed',
    );
  } finally {
    await app.cleanup();
  }
});

Deno.test('concurrent Player authentication consumes a code at most once', async () => {
  const app = await createTestApp({ tokenGenerator: () => crypto.randomUUID() });
  try {
    await authenticateMod(app.handler);
    const responses = await Promise.all([
      authenticatePlayer(app.handler),
      authenticatePlayer(app.handler),
    ]);
    const statuses = responses.map((response) => response.status).sort();
    assertEquals(statuses[0], 201, 'one concurrent request should succeed');
    assertEquals(statuses[1], 400, 'the replay should fail');
  } finally {
    await app.cleanup();
  }
});

Deno.test('invalid JSON and invalid request shapes return 400', async () => {
  const app = await createTestApp();
  try {
    const invalidJson = await app.handler(
      new Request(MOD_AUTH_PATH, {
        method: 'POST',
        headers: {
          authorization: `Bearer ${API_KEY}`,
          'content-type': 'application/json',
        },
        body: '{',
      }),
    );
    await assertErrorResponse(invalidJson, 400);

    const invalidShape = await app.handler(jsonRequest(MOD_AUTH_PATH, {
      code: 'XAU1-98B1',
      playerUUID: PLAYER_UUID,
      playerProfileName: 123,
    }));
    await assertErrorResponse(invalidShape, 400);

    await assertErrorResponse(await authenticateMod(app.handler, 'not-a-code'), 400);
  } finally {
    await app.cleanup();
  }
});
