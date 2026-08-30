import { type AppConfig, configFromEnv } from '../src/config.ts';
import { DurableAuthStore } from '../src/auth/store.ts';
import { createHandler } from '../src/index.ts';
import { DurableProgramStore } from '../src/programs/store.ts';
import { ProgramOperationConflictError, type ProgramStore } from '../src/programs/types.ts';

const MOD_AUTH_PATH = 'http://localhost/modapi/v1/authentication';
const PLAYER_AUTH_PATH = 'http://localhost/playerapi/v1/authenticate';
const PROGRAMS_PATH = 'http://localhost/playerapi/v1/programs';
const API_KEY = 'test-api-key';
const PLAYER_UUID = '123e4567-e89b-12d3-a456-426614174000';

type TestApp = {
  handler: (request: Request) => Promise<Response>;
  config: AppConfig;
  cleanup: () => Promise<void>;
};

async function createTestApp(
  playerProgramQuotaBytes: number,
  programStore?: ProgramStore,
): Promise<TestApp> {
  const storageDir = await Deno.makeTempDir({ prefix: 'cobbleware-program-test-' });
  const config = configFromEnv({
    apiKey: API_KEY,
    storageDir,
    playerProgramQuotaBytes,
  });
  const authStore = await DurableAuthStore.open(config);

  return {
    handler: await createHandler({ config, store: authStore, programStore }),
    config,
    cleanup: () => Deno.remove(storageDir, { recursive: true }),
  };
}

function jsonRequest(url: string, body: unknown): Request {
  return new Request(url, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${API_KEY}`,
      'content-type': 'application/json',
    },
    body: JSON.stringify(body),
  });
}

async function authenticatePlayerSession(handler: TestApp['handler']): Promise<string> {
  const modResponse = await handler(
    jsonRequest(MOD_AUTH_PATH, {
      code: 'XAU1-98B1',
      playerUUID: PLAYER_UUID,
      playerProfileName: 'Alice',
    }),
  );
  assertEquals(modResponse.status, 201, 'Mod authentication should succeed');

  const playerResponse = await handler(
    jsonRequest(PLAYER_AUTH_PATH, { code: 'XAU1-98B1' }),
  );
  assertEquals(playerResponse.status, 201, 'Player authentication should succeed');
  const cookie = playerResponse.headers.get('set-cookie')?.split(';', 1)[0];
  if (cookie === undefined) {
    throw new Error('expected a session cookie');
  }
  return cookie;
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

Deno.test('Player program upload stores bytes and returns metadata', async () => {
  const app = await createTestApp(1024);
  try {
    const cookie = await authenticatePlayerSession(app.handler);
    const content = new Uint8Array([0, 1, 2, 3, 254, 255]);
    const form = new FormData();
    form.append(
      'program',
      new File([content], 'hello.eszip', { type: 'application/octet-stream' }),
    );
    form.append('name', '  My Program  ');

    const response = await app.handler(
      new Request(PROGRAMS_PATH, {
        method: 'POST',
        headers: { cookie, origin: 'http://localhost' },
        body: form,
      }),
    );
    assertEquals(response.status, 201, 'program upload should succeed');
    assertEquals(
      response.headers.get('location'),
      '/playerapi/v1/programs/P2TENFMHCXWWQ6VJVQXV26C75MNJGQI7J4S73VWH7TDKWB756DRQ',
      'upload should include the created program location',
    );

    const metadata = await response.json();
    assertEquals(metadata.name, 'My Program', 'upload should normalize the program name');
    assertEquals(metadata.sizeBytes, content.byteLength, 'metadata should report payload size');
    if (!/^[A-Z2-7]{52}$/.test(metadata.id)) {
      throw new Error(`expected a Base32 program id, got ${metadata.id}`);
    }
    assertEquals(
      response.headers.get('location'),
      `/playerapi/v1/programs/${metadata.id}`,
      'upload location should use the program id',
    );

    const programPath = `${app.config.storageDir}/programs/${PLAYER_UUID}/${metadata.id}.eszip`;
    const stored = await Deno.readFile(programPath);
    assertEquals(
      JSON.stringify([...stored]),
      JSON.stringify([...content]),
      'stored bytes must be exact',
    );

    const reloaded = new DurableProgramStore(app.config);
    const list = await reloaded.list(PLAYER_UUID);
    assertEquals(list.programs.length, 1, 'program metadata should survive store reload');
    assertEquals(list.usedBytes, content.byteLength, 'stored bytes should count against quota');
  } finally {
    await app.cleanup();
  }
});

Deno.test('Program upload derives a name from the filename and enforces quota', async () => {
  const app = await createTestApp(3);
  try {
    const cookie = await authenticatePlayerSession(app.handler);
    const form = new FormData();
    form.append('program', new File([new Uint8Array([1, 2, 3, 4])], 'folder\\derived.eszip'));

    const response = await app.handler(
      new Request(PROGRAMS_PATH, {
        method: 'POST',
        headers: { cookie, origin: 'http://localhost' },
        body: form,
      }),
    );
    await assertErrorResponse(response, 413);

    const listResponse = await app.handler(
      new Request(PROGRAMS_PATH, { headers: { cookie } }),
    );
    assertEquals(listResponse.status, 200, 'program listing should remain available');
    const list = await listResponse.json();
    assertEquals(list.programs.length, 0, 'rejected uploads must not create metadata');
    assertEquals(list.usedBytes, 0, 'rejected uploads must not consume quota');
  } finally {
    await app.cleanup();
  }
});

Deno.test('Player can delete an owned program', async () => {
  const app = await createTestApp(1024);
  try {
    const cookie = await authenticatePlayerSession(app.handler);
    const form = new FormData();
    form.append('program', new File([new Uint8Array([1, 2])], 'delete-me.eszip'));
    const uploadResponse = await app.handler(
      new Request(PROGRAMS_PATH, {
        method: 'POST',
        headers: { cookie, origin: 'http://localhost' },
        body: form,
      }),
    );
    assertEquals(uploadResponse.status, 201, 'program upload should succeed');
    const metadata = await uploadResponse.json();

    const deleteResponse = await app.handler(
      new Request(`${PROGRAMS_PATH}/${metadata.id}`, {
        method: 'DELETE',
        headers: { cookie, origin: 'http://localhost' },
      }),
    );
    assertEquals(deleteResponse.status, 204, 'program deletion should succeed');
    assertEquals(await deleteResponse.text(), '', 'program deletion should have no body');

    await assertErrorResponse(
      await app.handler(
        new Request(`${PROGRAMS_PATH}/${metadata.id}`, {
          method: 'DELETE',
          headers: { cookie, origin: 'http://localhost' },
        }),
      ),
      404,
    );
    const programPath = `${app.config.storageDir}/programs/${PLAYER_UUID}/${metadata.id}.eszip`;
    try {
      await Deno.stat(programPath);
      throw new Error('deleted program file still exists');
    } catch (error) {
      if (!(error instanceof Deno.errors.NotFound)) {
        throw error;
      }
    }
  } finally {
    await app.cleanup();
  }
});

Deno.test('Program operations conflict per player without blocking other players', async () => {
  const app = await createTestApp(1024);
  try {
    const store = new DurableProgramStore(app.config);
    const firstUpload = store.upload('player-one', new Uint8Array([1]), 'one');

    let conflictError: unknown;
    try {
      await store.upload('player-one', new Uint8Array([2]), 'two');
    } catch (error) {
      conflictError = error;
    }
    if (!(conflictError instanceof ProgramOperationConflictError)) {
      throw new Error(`expected a player operation conflict, got ${String(conflictError)}`);
    }

    const otherPlayerUpload = await store.upload(
      'player-two',
      new Uint8Array([2]),
      'two',
    );
    assertEquals(otherPlayerUpload.created, true, 'different players should not conflict');
    await firstUpload;
  } finally {
    await app.cleanup();
  }
});

Deno.test('Program operation conflicts return HTTP 409', async () => {
  const conflict = () => {
    throw new ProgramOperationConflictError('another program operation is in progress');
  };
  const conflictStore: ProgramStore = {
    list: async () => conflict(),
    upload: async () => conflict(),
    delete: async () => conflict(),
  };
  const app = await createTestApp(1024, conflictStore);
  try {
    const cookie = await authenticatePlayerSession(app.handler);
    await assertErrorResponse(
      await app.handler(new Request(PROGRAMS_PATH, { headers: { cookie } })),
      409,
    );
  } finally {
    await app.cleanup();
  }
});
