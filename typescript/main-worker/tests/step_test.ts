import { Common, create, Effects, Events, fromBinary, Program, toBinary } from '@cobbleware/core';
import { type AppConfig, configFromEnv } from '../src/config.ts';
import { DurableAuthStore } from '../src/auth/store.ts';
import { createHandler } from '../src/index.ts';
import type { ActiveProgram, ProgramStore } from '../src/programs/types.ts';
import type { ProgramRunner } from '../src/programs/start-states.ts';

const API_KEY = 'test-api-key';
const PLAYER_UUID = '123e4567-e89b-12d3-a456-426614174000';
const PROGRAM_ID = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';
const STEP_PATH = `http://localhost/playerapi/v1/programs/${PROGRAM_ID}/step`;

const activeProgram: ActiveProgram = {
  metadata: {
    id: PROGRAM_ID,
    name: 'test',
    uploadedAt: '2026-09-03T00:00:00.000Z',
    sizeBytes: 2,
  },
  content: new Uint8Array([7, 8]),
};

type TestApp = {
  handler: (request: Request) => Promise<Response>;
  cleanup: () => Promise<void>;
};

async function createTestApp(
  readActive: ProgramStore['readActive'],
  step: ProgramRunner['step'],
): Promise<TestApp> {
  const storageDir = await Deno.makeTempDir({ prefix: 'cobbleware-step-test-' });
  const config: AppConfig = configFromEnv({ apiKey: API_KEY, storageDir });
  const authStore = await DurableAuthStore.open(config);
  const programStore: ProgramStore = {
    list: () => Promise.resolve({ programs: [], quotaBytes: 0, usedBytes: 0, remainingBytes: 0 }),
    readActive,
    upload: () => Promise.resolve({ metadata: activeProgram.metadata, created: false }),
  };
  const runner: ProgramRunner = {
    startStates: () => Promise.resolve({}),
    step,
  };

  return {
    handler: await createHandler({ config, store: authStore, programStore, programRunner: runner }),
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
    jsonRequest('http://localhost/modapi/v1/authentication', {
      code: 'XAU1-98B1',
      playerUUID: PLAYER_UUID,
      playerProfileName: 'Alice',
    }),
  );
  assertEquals(modResponse.status, 201, 'Mod authentication should succeed');

  const playerResponse = await handler(
    jsonRequest('http://localhost/playerapi/v1/authenticate', { code: 'XAU1-98B1' }),
  );
  assertEquals(playerResponse.status, 201, 'Player authentication should succeed');
  const cookie = playerResponse.headers.get('set-cookie')?.split(';', 1)[0];
  if (cookie === undefined) {
    throw new Error('expected a session cookie');
  }
  return cookie;
}

function encodeStepRequest(state = [1, 2, 3]): Uint8Array {
  return toBinary(
    Program.StepRequestSchema,
    create(Program.StepRequestSchema, {
      executionId: create(Common.UuidSchema, { high: 1n, low: 2n }),
      state: new Uint8Array(state),
      event: create(Events.EventSchema, {
        event: {
          case: 'puterPlaced',
          value: create(Events.PuterPlacedSchema, {
            levelKey: create(Common.IdentifierSchema, { ns: 'minecraft', path: 'overworld' }),
            pos: create(Common.Vec3Schema, { x: 1, y: 2, z: 3 }),
          }),
        },
      }),
    }),
  );
}

function encodeStepResponse(): Uint8Array {
  return toBinary(
    Program.StepResponseSchema,
    create(Program.StepResponseSchema, {
      state: new Uint8Array([9, 8]),
      effects: [
        create(Effects.EffectSchema, {
          effect: { case: 'sendChat', value: create(Effects.SendChatSchema, { msg: 'hello' }) },
        }),
        create(Effects.EffectSchema, {
          effect: { case: 'emitBeep', value: create(Effects.EmitBeepSchema, { millis: 250 }) },
        }),
      ],
    }),
  );
}

function stepRequest(
  body: Uint8Array,
  cookie: string,
  method = 'POST',
  contentType = 'application/octet-stream',
  accept?: string,
): Request {
  const headers = new Headers({ cookie, origin: 'http://localhost', 'content-type': contentType });
  if (accept !== undefined) {
    headers.set('accept', accept);
  }
  return new Request(STEP_PATH, {
    method,
    headers,
    body: new Blob([body.slice().buffer as ArrayBuffer]),
  });
}

function jsonStepRequest(body: unknown, cookie: string, accept?: string): Request {
  const headers = new Headers({
    cookie,
    origin: 'http://localhost',
    'content-type': 'application/json; charset=utf-8',
  });
  if (accept !== undefined) {
    headers.set('accept', accept);
  }
  return new Request(STEP_PATH, { method: 'POST', headers, body: JSON.stringify(body) });
}

function stepRequestJson(): Record<string, unknown> {
  return {
    executionId: { high: '1', low: '2' },
    state: 'AQID',
    event: {
      puterPlaced: {
        levelKey: { ns: 'minecraft', path: 'overworld' },
        pos: { x: 1, y: 2, z: 3 },
      },
    },
  };
}

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) {
    throw new Error(`${message}: expected ${String(expected)}, got ${String(actual)}`);
  }
}

async function assertJsonError(response: Response, status: number): Promise<void> {
  assertEquals(response.status, status, 'unexpected response status');
  const body = await response.json();
  if (typeof body.error !== 'string') {
    throw new Error(`expected JSON error, got ${JSON.stringify(body)}`);
  }
}

Deno.test('Player program step executes the active program and returns binary output', async () => {
  let receivedPlayer: string | undefined;
  let receivedProgram: ActiveProgram | undefined;
  let receivedBody: Uint8Array | undefined;
  const app = await createTestApp(
    () => Promise.resolve(activeProgram),
    (request, playerUUID, program, body) => {
      receivedPlayer = playerUUID;
      receivedProgram = program;
      receivedBody = body;
      assertEquals(request.method, 'POST', 'runner should receive the player request method');
      return Promise.resolve(binaryResponse(encodeStepResponse(), 200));
    },
  );
  try {
    const cookie = await authenticatePlayerSession(app.handler);
    const requestBody = encodeStepRequest();
    const response = await app.handler(stepRequest(requestBody, cookie));
    assertEquals(response.status, 200, 'step should succeed');
    assertEquals(
      response.headers.get('content-type'),
      'application/octet-stream',
      'step should return binary content',
    );

    const body = new Uint8Array(await response.arrayBuffer());
    const decoded = fromBinary(Program.StepResponseSchema, body);
    assertEquals(
      JSON.stringify([...decoded.state]),
      JSON.stringify([9, 8]),
      'next state should round trip',
    );
    assertEquals(decoded.effects.length, 2, 'effects should round trip');
    assertEquals(receivedPlayer, PLAYER_UUID, 'runner should receive the authenticated player');
    if (receivedProgram !== activeProgram) {
      throw new Error('runner should receive the active program');
    }
    if (receivedBody === undefined) throw new Error('runner should receive the request body');
    assertEquals(
      JSON.stringify([...receivedBody]),
      JSON.stringify([...requestBody]),
      'runner should receive the exact request bytes',
    );
  } finally {
    await app.cleanup();
  }
});

Deno.test('Player program step accepts and returns ProtoJSON', async () => {
  let receivedBody: Uint8Array | undefined;
  const app = await createTestApp(
    () => Promise.resolve(activeProgram),
    (_request, _playerUUID, _program, body) => {
      receivedBody = body;
      return Promise.resolve(binaryResponse(encodeStepResponse(), 200));
    },
  );
  try {
    const cookie = await authenticatePlayerSession(app.handler);
    const response = await app.handler(
      jsonStepRequest(stepRequestJson(), cookie, 'application/json'),
    );
    assertEquals(response.status, 200, 'ProtoJSON step should succeed');
    assertEquals(
      response.headers.get('content-type'),
      'application/json; charset=utf-8',
      'ProtoJSON step should return JSON content',
    );
    assertEquals(
      JSON.stringify(await response.json()),
      JSON.stringify({
        state: 'CQg=',
        effects: [
          { sendChat: { msg: 'hello' } },
          { emitBeep: { millis: 250 } },
        ],
      }),
      'StepResponse should use the canonical ProtoJSON form',
    );
    if (receivedBody === undefined) throw new Error('runner should receive the converted request');
    const request = fromBinary(Program.StepRequestSchema, receivedBody);
    assertEquals(request.executionId?.high, 1n, 'ProtoJSON UUID high value should convert');
    assertEquals(request.event?.event.case, 'puterPlaced', 'ProtoJSON oneof should convert');
  } finally {
    await app.cleanup();
  }
});

Deno.test('Player program step defaults a ProtoJSON request to a binary response', async () => {
  const app = await createTestApp(
    () => Promise.resolve(activeProgram),
    () => Promise.resolve(binaryResponse(encodeStepResponse(), 200)),
  );
  try {
    const cookie = await authenticatePlayerSession(app.handler);
    const response = await app.handler(jsonStepRequest(stepRequestJson(), cookie));
    assertEquals(response.status, 200, 'ProtoJSON step should succeed');
    assertEquals(
      response.headers.get('content-type'),
      'application/octet-stream',
      'ProtoJSON input should not select a JSON response',
    );
    const body = new Uint8Array(await response.arrayBuffer());
    const decoded = fromBinary(Program.StepResponseSchema, body);
    assertEquals(
      JSON.stringify([...decoded.state]),
      JSON.stringify([9, 8]),
      'response should be binary',
    );
  } finally {
    await app.cleanup();
  }
});

Deno.test('Player program step rejects invalid ProtoJSON without invoking the program', async () => {
  let runnerCalled = false;
  const app = await createTestApp(
    () => Promise.resolve(activeProgram),
    () => {
      runnerCalled = true;
      return Promise.resolve(binaryResponse(encodeStepResponse(), 200));
    },
  );
  try {
    const cookie = await authenticatePlayerSession(app.handler);
    await assertJsonError(
      await app.handler(jsonStepRequest({ ...stepRequestJson(), unknownField: true }, cookie)),
      400,
    );
    if (runnerCalled) throw new Error('invalid ProtoJSON must not invoke the program');
  } finally {
    await app.cleanup();
  }
});

Deno.test('Player program step enforces HTTP policy and forwards opaque request bodies', async () => {
  let runnerCalled = false;
  const app = await createTestApp(
    () => Promise.resolve(activeProgram),
    () => {
      runnerCalled = true;
      return Promise.resolve(binaryResponse(encodeStepResponse(), 200));
    },
  );
  try {
    const cookie = await authenticatePlayerSession(app.handler);
    const malformedBodyResponse = await app.handler(
      stepRequest(new Uint8Array([1, 2, 3]), cookie),
    );
    assertEquals(
      malformedBodyResponse.status,
      200,
      'malformed protobuf messages should be forwarded without Main Worker verification',
    );
    if (!runnerCalled) throw new Error('opaque request bodies must reach the program runner');
    runnerCalled = false;
    await assertJsonError(
      await app.handler(stepRequest(encodeStepRequest(), cookie, 'POST', 'text/plain')),
      415,
    );
    const noOrigin = new Request(STEP_PATH, {
      method: 'POST',
      headers: { cookie, 'content-type': 'application/octet-stream' },
      body: new Blob([encodeStepRequest().buffer as ArrayBuffer]),
    });
    await assertJsonError(await app.handler(noOrigin), 403);
    const wrongMethod = await app.handler(
      new Request(STEP_PATH, { method: 'GET', headers: { cookie, origin: 'http://localhost' } }),
    );
    assertEquals(wrongMethod.status, 405, 'wrong method should be rejected');
    assertEquals(wrongMethod.headers.get('allow'), 'POST', 'Allow should name POST');
    if (runnerCalled) throw new Error('invalid requests must not invoke the program');
  } finally {
    await app.cleanup();
  }

  const missingApp = await createTestApp(
    () => Promise.resolve(null),
    () => Promise.resolve(binaryResponse(encodeStepResponse(), 200)),
  );
  try {
    const cookie = await authenticatePlayerSession(missingApp.handler);
    const response = await missingApp.handler(stepRequest(encodeStepRequest(), cookie));
    assertEquals(response.status, 404, 'missing active program should be rejected');
  } finally {
    await missingApp.cleanup();
  }
});

Deno.test('Player program step exposes program failures as binary ErrorResponse', async () => {
  const app = await createTestApp(
    () => Promise.resolve(activeProgram),
    () => Promise.resolve(binaryResponse(encodeErrorResponse('program exploded'), 500)),
  );
  try {
    const cookie = await authenticatePlayerSession(app.handler);
    const response = await app.handler(
      stepRequest(
        encodeStepRequest(),
        cookie,
        'POST',
        'application/octet-stream',
        'application/json',
      ),
    );
    assertEquals(response.status, 500, 'program failure should return 500');
    assertEquals(
      response.headers.get('content-type'),
      'application/octet-stream',
      'program failure should return binary content',
    );
    const body = new Uint8Array(await response.arrayBuffer());
    const errorResponse = fromBinary(Program.ErrorResponseSchema, body);
    if (errorResponse.error !== 'program exploded') {
      throw new Error(`expected program error message, got ${errorResponse.error}`);
    }
  } finally {
    await app.cleanup();
  }
});

Deno.test('Player program step forwards untrusted program response bytes unchanged', async () => {
  const responseBody = new Uint8Array([1, 2, 3]);
  const app = await createTestApp(
    () => Promise.resolve(activeProgram),
    () => Promise.resolve(binaryResponse(responseBody, 200, 'text/plain')),
  );
  try {
    const cookie = await authenticatePlayerSession(app.handler);
    const response = await app.handler(stepRequest(encodeStepRequest(), cookie));
    assertEquals(response.status, 200, 'program response status should be preserved');
    assertEquals(
      response.headers.get('content-type'),
      'text/plain',
      'program response content type should be preserved',
    );
    assertEquals(
      JSON.stringify([...new Uint8Array(await response.arrayBuffer())]),
      JSON.stringify([...responseBody]),
      'program response bytes should be preserved',
    );
  } finally {
    await app.cleanup();
  }
});

Deno.test('Player program step returns 502 when a ProtoJSON response cannot be converted', async () => {
  const app = await createTestApp(
    () => Promise.resolve(activeProgram),
    () => Promise.resolve(binaryResponse(new Uint8Array([1, 2, 3]), 200)),
  );
  try {
    const cookie = await authenticatePlayerSession(app.handler);
    await assertJsonError(
      await app.handler(
        stepRequest(
          encodeStepRequest(),
          cookie,
          'POST',
          'application/octet-stream',
          'application/json',
        ),
      ),
      502,
    );
  } finally {
    await app.cleanup();
  }
});

function encodeErrorResponse(error: string): Uint8Array {
  return toBinary(Program.ErrorResponseSchema, create(Program.ErrorResponseSchema, { error }));
}

function binaryResponse(
  body: Uint8Array,
  status: number,
  contentType = 'application/octet-stream',
): Response {
  return new Response(new Blob([body.slice().buffer as ArrayBuffer]), {
    status,
    headers: { 'content-type': contentType },
  });
}
