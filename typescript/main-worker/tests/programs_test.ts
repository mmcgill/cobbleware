import { type AppConfig, configFromEnv } from '../src/config.ts';
import { create, Program, toBinary } from '@cobbleware/core';
import { DurableAuthStore } from '../src/auth/store.ts';
import { BYTES_PER_MEBIBYTE } from '../src/config.ts';
import { createHandler } from '../src/index.ts';
import { handleProgramCollection } from '../src/programs/handlers.ts';
import { DurableProgramStore, workerServicePath } from '../src/programs/store.ts';
import {
  PROGRAM_OPERATIONS_WORKER_UUID,
  type ProgramOperations,
  RuntimeProgramOperations,
} from '../src/programs/worker.ts';
import {
  type ActiveProgram,
  ProgramOperationConflictError,
  type ProgramStore,
} from '../src/programs/types.ts';

const MOD_AUTH_PATH = 'http://localhost/modapi/v1/authentication';
const PLAYER_AUTH_PATH = 'http://localhost/playerapi/v1/authenticate';
const PROGRAMS_PATH = 'http://localhost/playerapi/v1/programs';
const START_STATES_PATH =
  `${PROGRAMS_PATH}/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/startStates`;
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
  operations: ProgramOperations = successfulProgramOperations(),
): Promise<TestApp> {
  const storageDir = await Deno.makeTempDir({ prefix: 'cobbleware-program-test-' });
  const config = configFromEnv({
    apiKey: API_KEY,
    storageDir,
    playerProgramQuotaBytes,
  });
  const authStore = await DurableAuthStore.open(config);

  return {
    handler: await createHandler({
      config,
      store: authStore,
      programStore,
      programOperations: operations,
    }),
    config,
    cleanup: () => Deno.remove(storageDir, { recursive: true }),
  };
}

function successfulProgramOperations(): ProgramOperations {
  return {
    exclusive: (operation) => operation({ startStates: () => Promise.resolve({ default: [1] }) }),
  };
}

function worker(fetch: CobblewareUserWorker['fetch']): CobblewareUserWorker {
  return { closed: Promise.resolve({}), retire: () => {}, fetch };
}

class Deferred<T> {
  readonly promise: Promise<T>;
  private resolvePromise!: (value: T) => void;

  constructor() {
    this.promise = new Promise((resolve) => {
      this.resolvePromise = resolve;
    });
  }

  resolve(value: T): void {
    this.resolvePromise(value);
  }
}

class TransientWorker implements CobblewareUserWorker {
  readonly closedDeferred = new Deferred<CobblewareUserWorkerClosed>();
  readonly closed = this.closedDeferred.promise;
  readonly responseDeferred = new Deferred<Response>();
  readonly requests: Request[] = [];
  retireCalls: number[] = [];

  retire(shutdownMs: number): void {
    this.retireCalls.push(shutdownMs);
  }

  fetch(request: Request): Promise<Response> {
    this.requests.push(request);
    return this.responseDeferred.promise;
  }

  respond(response: Response): void {
    this.responseDeferred.resolve(response);
  }

  close(): void {
    this.closedDeferred.resolve({});
  }
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

Deno.test('Player program start states returns names for the active program', async () => {
  const activeProgram: ActiveProgram = {
    metadata: {
      id: 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',
      name: 'test.eszip',
      uploadedAt: '2026-09-09T00:00:00.000Z',
      sizeBytes: 2,
    },
    content: new Uint8Array([1, 2]),
  };
  const programStore: ProgramStore = {
    list: () =>
      Promise.resolve({
        programs: [activeProgram.metadata],
        quotaBytes: 2,
        usedBytes: 2,
        remainingBytes: 0,
      }),
    readActive: () => Promise.resolve(activeProgram),
    upload: () => Promise.resolve({ metadata: activeProgram.metadata, created: false }),
    run: () =>
      Promise.resolve({
        program: activeProgram,
        worker: worker(() =>
          Promise.resolve(
            startStatesResponse({ default: [1], paused: [2] }),
          )
        ),
      }),
  };
  const app = await createTestApp(1024, programStore, {
    exclusive: (operation) =>
      operation({ startStates: () => Promise.resolve({ default: [1], paused: [2] }) }),
  });
  try {
    const cookie = await authenticatePlayerSession(app.handler);
    const response = await app.handler(new Request(START_STATES_PATH, { headers: { cookie } }));
    assertEquals(response.status, 200, 'active program start states should be returned');
    assertEquals(
      JSON.stringify(await response.json()),
      JSON.stringify({ startStates: ['default', 'paused'] }),
      'start state names should be returned in program order',
    );
  } finally {
    await app.cleanup();
  }
});

Deno.test('Program store creates an active-program worker with the requested memory quota', async () => {
  const app = await createTestApp(1024);
  const store = new DurableProgramStore(app.config);
  const previousRuntime = Object.getOwnPropertyDescriptor(globalThis, 'EdgeRuntime');
  let options: CobblewareUserWorkerOptions | undefined;
  Object.defineProperty(globalThis, 'EdgeRuntime', {
    configurable: true,
    value: {
      userWorkers: {
        create: (value: CobblewareUserWorkerOptions) => {
          options = value;
          return Promise.resolve(worker(() => Promise.resolve(new Response())));
        },
        memStats: () => Promise.resolve({}),
      },
    },
  });

  try {
    const uploaded = await store.upload(PLAYER_UUID, new Uint8Array([1, 2, 3]), 'test.eszip');
    const running = await store.run(PLAYER_UUID, { memoryQuotaBytes: 3 * BYTES_PER_MEBIBYTE });
    if (options === undefined) throw new Error('ProgramStore.run did not create a worker');
    assertEquals(options.memoryLimitMb, 3, 'worker memory limit should be in MiB');
    assertEquals(options.maybeEszip.byteLength, 3, 'worker should receive active program content');
    if (!options.servicePath.endsWith(`-${uploaded.metadata.id}`)) {
      throw new Error(`worker service path changed: ${options.servicePath}`);
    }
    assertEquals(
      running.program.metadata.id,
      uploaded.metadata.id,
      'run should return its program',
    );
  } finally {
    if (previousRuntime === undefined) {
      delete (globalThis as { EdgeRuntime?: unknown }).EdgeRuntime;
    } else {
      Object.defineProperty(globalThis, 'EdgeRuntime', previousRuntime);
    }
    await app.cleanup();
  }
});

Deno.test('Transient program operations serialize workers and wait for retirement', async () => {
  const previousRuntime = Object.getOwnPropertyDescriptor(globalThis, 'EdgeRuntime');
  const firstWorker = new TransientWorker();
  const secondWorker = new TransientWorker();
  const workers = [firstWorker, secondWorker];
  const options: CobblewareUserWorkerOptions[] = [];
  Object.defineProperty(globalThis, 'EdgeRuntime', {
    configurable: true,
    value: {
      userWorkers: {
        create: (value: CobblewareUserWorkerOptions) => {
          options.push(value);
          const worker = workers.shift();
          if (worker === undefined) throw new Error('unexpected transient worker creation');
          return Promise.resolve(worker);
        },
        memStats: () => Promise.resolve({}),
      },
    },
  });

  try {
    const operations = new RuntimeProgramOperations(3 * BYTES_PER_MEBIBYTE);
    const first = operations.exclusive((locked) => locked.startStates(new Uint8Array([1])));
    await waitFor(() => firstWorker.requests.length === 1);

    const second = operations.exclusive((locked) => locked.startStates(new Uint8Array([2])));
    await Promise.resolve();
    assertEquals(options.length, 1, 'second operation created a worker before the first completed');

    firstWorker.respond(startStatesResponse({ first: [1] }));
    await waitFor(() => firstWorker.retireCalls.length === 1);
    assertEquals(firstWorker.retireCalls[0], 0, 'first worker should retire immediately');
    assertEquals(options.length, 1, 'second worker started before the first closed');
    firstWorker.close();
    await first;

    await waitFor(() => secondWorker.requests.length === 1);
    secondWorker.respond(startStatesResponse({ second: [2] }));
    await waitFor(() => secondWorker.retireCalls.length === 1);
    secondWorker.close();
    await second;

    assertEquals(
      options[0]?.servicePath,
      workerServicePath(PROGRAM_OPERATIONS_WORKER_UUID, 'program-operations'),
      'first operation used the wrong shared service path',
    );
    assertEquals(
      options[1]?.servicePath,
      options[0]?.servicePath,
      'program operations must share one Runtime pool key',
    );
    assertEquals(options[0]?.memoryLimitMb, 3, 'transient worker memory quota changed');
    assertEquals(
      JSON.stringify([...options[1]!.maybeEszip]),
      JSON.stringify([2]),
      'second operation did not receive its candidate program bytes',
    );
  } finally {
    if (previousRuntime === undefined) {
      delete (globalThis as { EdgeRuntime?: unknown }).EdgeRuntime;
    } else {
      Object.defineProperty(globalThis, 'EdgeRuntime', previousRuntime);
    }
  }
});

Deno.test('Program upload rejects a validation failure without activating the candidate', async () => {
  let uploaded = false;
  let notified = false;
  const store: ProgramStore = {
    list: () => Promise.reject(new Error('not used')),
    readActive: () => Promise.reject(new Error('not used')),
    upload: async (_playerUUID, _content, _name, validate) => {
      await validate?.();
      uploaded = true;
      throw new Error('not used');
    },
    run: () => Promise.reject(new Error('not used')),
  };
  const form = new FormData();
  form.append('program', new File([new Uint8Array([1])], 'invalid.eszip'));

  const response = await handleProgramCollection(
    new Request(PROGRAMS_PATH, {
      method: 'POST',
      headers: { origin: 'http://localhost' },
      body: form,
    }),
    store,
    {
      exclusive: (operation) =>
        operation({
          startStates: () => Promise.reject(new Error('invalid program')),
        }),
    },
    PLAYER_UUID,
    () => {
      notified = true;
      return Promise.resolve();
    },
  );

  await assertErrorResponse(response, 422);
  assertEquals(uploaded, false, 'validation failure should not store the candidate');
  assertEquals(notified, false, 'validation failure should not notify execution management');
});

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

async function waitFor(condition: () => boolean): Promise<void> {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    if (condition()) return;
    await Promise.resolve();
  }
  throw new Error('condition was not reached');
}

async function assertErrorResponse(response: Response, status: number): Promise<void> {
  assertEquals(response.status, status, 'unexpected response status');
  const body = await response.json();
  if (typeof body.error !== 'string') {
    throw new Error(`expected an error property, got ${JSON.stringify(body)}`);
  }
}

Deno.test('Program upload notifies execution management when the active program changes', async () => {
  const metadata = {
    id: 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',
    name: 'test.eszip',
    uploadedAt: '2026-10-02T00:00:00.000Z',
    sizeBytes: 1,
  };
  let created = true;
  const store: ProgramStore = {
    list: () => Promise.reject(new Error('not used')),
    readActive: () => Promise.reject(new Error('not used')),
    upload: () => Promise.resolve({ metadata, created }),
    run: () => Promise.reject(new Error('not used')),
  };
  const changes: Array<[string, string]> = [];

  for (const expectedStatus of [201, 200]) {
    const form = new FormData();
    form.append('program', new File([new Uint8Array([1])], 'test.eszip'));
    const response = await handleProgramCollection(
      new Request(PROGRAMS_PATH, {
        method: 'POST',
        headers: { origin: 'http://localhost' },
        body: form,
      }),
      store,
      successfulProgramOperations(),
      PLAYER_UUID,
      (playerId, programId) => {
        changes.push([playerId, programId]);
        return Promise.resolve();
      },
    );
    assertEquals(response.status, expectedStatus, 'upload response status');
    created = false;
  }

  assertEquals(
    JSON.stringify(changes),
    JSON.stringify([[PLAYER_UUID, metadata.id]]),
    'only an active-program change notifies execution management',
  );
});

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
    const active = await reloaded.readActive(PLAYER_UUID);
    if (active === null) throw new Error('active program should be readable');
    assertEquals(
      JSON.stringify([...active.content]),
      JSON.stringify([...content]),
      'active-program reads must return exact uploaded bytes',
    );
  } finally {
    await app.cleanup();
  }
});

Deno.test('Program upload derives a name from the filename and enforces the maximum size', async () => {
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

Deno.test('Uploading a replacement keeps only the new active program', async () => {
  const app = await createTestApp(1024);
  try {
    const cookie = await authenticatePlayerSession(app.handler);
    const form = new FormData();
    form.append('program', new File([new Uint8Array([1, 2])], 'old.eszip'));
    const uploadResponse = await app.handler(
      new Request(PROGRAMS_PATH, {
        method: 'POST',
        headers: { cookie, origin: 'http://localhost' },
        body: form,
      }),
    );
    assertEquals(uploadResponse.status, 201, 'program upload should succeed');
    const oldMetadata = await uploadResponse.json();

    const replacementForm = new FormData();
    replacementForm.append(
      'program',
      new File([new Uint8Array([3, 4, 5])], 'replacement.eszip'),
    );
    const replacementResponse = await app.handler(
      new Request(PROGRAMS_PATH, {
        method: 'POST',
        headers: { cookie, origin: 'http://localhost' },
        body: replacementForm,
      }),
    );
    assertEquals(replacementResponse.status, 201, 'replacement upload should succeed');
    const newMetadata = await replacementResponse.json();

    const listResponse = await app.handler(
      new Request(PROGRAMS_PATH, { headers: { cookie } }),
    );
    const list = await listResponse.json();
    assertEquals(list.programs.length, 1, 'replacement should leave one active program');
    assertEquals(list.programs[0].id, newMetadata.id, 'new program should be active');
    assertEquals(list.usedBytes, 3, 'quota usage should count only the active program');

    const oldProgramPath =
      `${app.config.storageDir}/programs/${PLAYER_UUID}/${oldMetadata.id}.eszip`;
    try {
      await Deno.stat(oldProgramPath);
      throw new Error('replaced program file still exists');
    } catch (error) {
      if (!(error instanceof Deno.errors.NotFound)) {
        throw error;
      }
    }

    const newProgramPath =
      `${app.config.storageDir}/programs/${PLAYER_UUID}/${newMetadata.id}.eszip`;
    await Deno.stat(newProgramPath);
  } finally {
    await app.cleanup();
  }
});

Deno.test('Replacement quota applies to the new active program only', async () => {
  const app = await createTestApp(4);
  try {
    const cookie = await authenticatePlayerSession(app.handler);
    const firstForm = new FormData();
    firstForm.append('program', new File([new Uint8Array([1, 2, 3])], 'first.eszip'));
    const firstResponse = await app.handler(
      new Request(PROGRAMS_PATH, {
        method: 'POST',
        headers: { cookie, origin: 'http://localhost' },
        body: firstForm,
      }),
    );
    assertEquals(firstResponse.status, 201, 'first upload should succeed');

    const replacementForm = new FormData();
    replacementForm.append('program', new File([new Uint8Array([4, 5, 6, 7])], 'second.eszip'));
    const replacementResponse = await app.handler(
      new Request(PROGRAMS_PATH, {
        method: 'POST',
        headers: { cookie, origin: 'http://localhost' },
        body: replacementForm,
      }),
    );
    assertEquals(replacementResponse.status, 201, 'replacement at the quota limit should succeed');
  } finally {
    await app.cleanup();
  }
});

Deno.test('Program upload removes unreferenced files before storing a replacement', async () => {
  const app = await createTestApp(1024);
  try {
    const playerDirectory = `${app.config.storageDir}/programs/${PLAYER_UUID}`;
    await Deno.mkdir(playerDirectory, { recursive: true });
    const orphanPath = `${playerDirectory}/orphan.eszip`;
    await Deno.writeFile(orphanPath, new Uint8Array([9, 9, 9]));
    await Deno.writeTextFile(
      `${playerDirectory}/programs.json`,
      JSON.stringify({ version: 1, programs: [] }),
    );

    const cookie = await authenticatePlayerSession(app.handler);
    const form = new FormData();
    form.append('program', new File([new Uint8Array([1])], 'new.eszip'));
    const response = await app.handler(
      new Request(PROGRAMS_PATH, {
        method: 'POST',
        headers: { cookie, origin: 'http://localhost' },
        body: form,
      }),
    );
    assertEquals(response.status, 201, 'upload should succeed after orphan recovery');

    try {
      await Deno.stat(orphanPath);
      throw new Error('unreferenced program file still exists');
    } catch (error) {
      if (!(error instanceof Deno.errors.NotFound)) {
        throw error;
      }
    }
  } finally {
    await app.cleanup();
  }
});

Deno.test('Program item and delete routes are unavailable', async () => {
  const app = await createTestApp(1024);
  try {
    const cookie = await authenticatePlayerSession(app.handler);
    const response = await app.handler(
      new Request(`${PROGRAMS_PATH}/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA`, {
        method: 'DELETE',
        headers: { cookie, origin: 'http://localhost' },
      }),
    );
    assertEquals(response.status, 404, 'program item route should not be supported');
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

Deno.test('Concurrent active-program reads for one player do not conflict', async () => {
  const app = await createTestApp(1024);
  try {
    const store = new DurableProgramStore(app.config);
    const uploaded = await store.upload(PLAYER_UUID, new Uint8Array([1, 2, 3]), 'program');

    const [first, second] = await Promise.all([
      store.readActive(PLAYER_UUID),
      store.readActive(PLAYER_UUID),
    ]);
    if (first === null || second === null) {
      throw new Error('concurrent reads should find the active program');
    }
    assertEquals(
      first.metadata.id,
      uploaded.metadata.id,
      'first read should find the active program',
    );
    assertEquals(
      second.metadata.id,
      uploaded.metadata.id,
      'second read should find the active program',
    );
    assertEquals(
      JSON.stringify([...first.content]),
      JSON.stringify([1, 2, 3]),
      'first read should return the stored content',
    );
    assertEquals(
      JSON.stringify([...second.content]),
      JSON.stringify([1, 2, 3]),
      'second read should return the stored content',
    );
  } finally {
    await app.cleanup();
  }
});

Deno.test('Program operation conflicts return HTTP 409', async () => {
  const conflict = () => {
    throw new ProgramOperationConflictError('another program operation is in progress');
  };
  const conflictStore: ProgramStore = {
    list: () => conflict(),
    readActive: () => conflict(),
    upload: () => conflict(),
    run: () => conflict(),
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
