import { create, Program, toBinary } from '@cobbleware/core';
import { type AppConfig, configFromEnv } from '../src/config.ts';
import { DurableAuthStore } from '../src/auth/store.ts';
import { createHandler } from '../src/index.ts';
import type { ActiveProgram, ProgramStore } from '../src/programs/types.ts';

const API_KEY = 'test-api-key';
const PROGRAM_PATH = 'http://localhost/modapi/v1/players/ABC-Player/program';
const PLAYER_UUID = 'abc-player';

type TestApp = {
  handler: (request: Request) => Promise<Response>;
  cleanup: () => Promise<void>;
};

const activeProgram: ActiveProgram = {
  metadata: {
    id: 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',
    name: 'test',
    uploadedAt: '2026-09-03T00:00:00.000Z',
    sizeBytes: 2,
  },
  content: new Uint8Array([7, 8]),
};

async function createTestApp(
  readActive: ProgramStore['readActive'],
  fetch: CobblewareUserWorker['fetch'],
): Promise<TestApp> {
  const storageDir = await Deno.makeTempDir({ prefix: 'cobbleware-modapi-test-' });
  const config: AppConfig = configFromEnv({ apiKey: API_KEY, storageDir });
  const authStore = await DurableAuthStore.open(config);
  const programStore: ProgramStore = {
    list: () => Promise.resolve({ programs: [], quotaBytes: 0, usedBytes: 0, remainingBytes: 0 }),
    readActive,
    upload: () => Promise.resolve({ metadata: activeProgram.metadata, created: false }),
    run: () => Promise.resolve({ program: activeProgram, worker: worker(fetch) }),
  };

  return {
    handler: await createHandler({ config, store: authStore, programStore }),
    cleanup: () => Deno.remove(storageDir, { recursive: true }),
  };
}

function worker(fetch: CobblewareUserWorker['fetch']): CobblewareUserWorker {
  return { closed: Promise.resolve({}), retire: () => {}, fetch };
}

function startStatesResponse(startStates: Record<string, number[]>): Response {
  const content = toBinary(
    Program.StartStatesResponseSchema,
    create(Program.StartStatesResponseSchema, {
      startStates: Object.entries(startStates).map(([stateName, state]) =>
        create(Program.StartStateSchema, { stateName, state: new Uint8Array(state) })
      ),
    }),
  );
  return new Response(content, { headers: { 'content-type': 'application/octet-stream' } });
}

function authorizedRequest(method = 'GET'): Request {
  return new Request(PROGRAM_PATH, {
    method,
    headers: { authorization: `Bearer ${API_KEY}` },
  });
}

async function assertProgramError(response: Response): Promise<void> {
  assertEquals(response.status, 200, 'program errors should use HTTP 200');
  const body = await response.json();
  if (typeof body.programError !== 'string') {
    throw new Error(`expected programError, got ${JSON.stringify(body)}`);
  }
}

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) {
    throw new Error(`${message}: expected ${String(expected)}, got ${String(actual)}`);
  }
}

Deno.test('Mod program query requires bearer authentication', async () => {
  const app = await createTestApp(
    () => Promise.resolve(activeProgram),
    () => Promise.resolve(startStatesResponse({ one: [1] })),
  );
  try {
    const response = await app.handler(new Request(PROGRAM_PATH));
    assertEquals(response.status, 401, 'missing Mod authentication should be rejected');
  } finally {
    await app.cleanup();
  }
});

Deno.test('Mod program query returns active program metadata and states', async () => {
  let requestedPlayer: string | undefined;
  const app = await createTestApp(
    (playerUUID) => {
      requestedPlayer = playerUUID;
      return Promise.resolve(activeProgram);
    },
    (request) => {
      assertEquals(request.url, 'http://cobbleware.local/startStates', 'program API path');
      return Promise.resolve(startStatesResponse({ one: [1], two: [2] }));
    },
  );
  try {
    const response = await app.handler(authorizedRequest());
    assertEquals(response.status, 200, 'valid program query should succeed');
    assertEquals(
      JSON.stringify(await response.json()),
      JSON.stringify({
        id: activeProgram.metadata.id,
        name: 'test',
        uploadedAt: '2026-09-03T00:00:00.000Z',
        sizeBytes: 2,
        startStates: { one: [1], two: [2] },
      }),
      'program metadata and start states should be returned as JSON',
    );
    assertEquals(requestedPlayer, PLAYER_UUID, 'player UUID should be normalized');
  } finally {
    await app.cleanup();
  }
});

Deno.test('Mod program query returns 404 without an active program', async () => {
  let called = false;
  const app = await createTestApp(
    () => Promise.resolve(null),
    () => {
      called = true;
      return Promise.resolve(startStatesResponse({}));
    },
  );
  try {
    const response = await app.handler(authorizedRequest());
    assertEquals(response.status, 404, 'missing active programs should return 404');
    if (called) throw new Error('runner should not run without an active program');
  } finally {
    await app.cleanup();
  }
});

Deno.test('Mod program query rejects methods other than GET', async () => {
  let called = false;
  const app = await createTestApp(
    () => {
      called = true;
      return Promise.resolve(activeProgram);
    },
    () => Promise.resolve(startStatesResponse({ one: [1] })),
  );
  try {
    const response = await app.handler(authorizedRequest('POST'));
    assertEquals(response.status, 405, 'unsupported methods should return 405');
    assertEquals(response.headers.get('allow'), 'GET', 'Allow should name GET');
    if (called) throw new Error('store should not run for an unsupported method');
  } finally {
    await app.cleanup();
  }
});

Deno.test('Mod program query converts program failures to programError', async () => {
  const app = await createTestApp(
    () => Promise.resolve(activeProgram),
    () => Promise.reject(new Error('program exploded')),
  );
  try {
    await assertProgramError(await app.handler(authorizedRequest()));
  } finally {
    await app.cleanup();
  }
});

Deno.test('Mod program query converts active-program read failures to programError', async () => {
  const app = await createTestApp(
    () => Promise.reject(new Error('program file could not be loaded')),
    () => Promise.resolve(startStatesResponse({ one: [1] })),
  );
  try {
    await assertProgramError(await app.handler(authorizedRequest()));
  } finally {
    await app.cleanup();
  }
});
