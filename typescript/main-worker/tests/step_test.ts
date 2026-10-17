import { create, fromBinary, toBinary } from '@cobbleware/core';
import { UuidSchema } from '@cobbleware/core/common';
import { EffectSchema, EmitBeepSchema, SendChatSchema } from '@cobbleware/core/effects';
import { EventSchema, StartedSchema } from '@cobbleware/core/events';
import {
  ErrorResponseSchema,
  StepRequestSchema,
  StepResponseSchema,
} from '@cobbleware/core/program';
import { type AppConfig, configFromEnv } from '../src/config.ts';
import { DurableAuthStore } from '../src/auth/store.ts';
import { createHandler } from '../src/index.ts';
import type { ActiveProgram, ProgramStore } from '../src/programs/types.ts';

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
  fetch: CobblewareUserWorker['fetch'],
): Promise<TestApp> {
  const storageDir = await Deno.makeTempDir({ prefix: 'cobbleware-step-test-' });
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
    StepRequestSchema,
    create(StepRequestSchema, {
      executionId: create(UuidSchema, { high: 1n, low: 2n }),
      state: new Uint8Array(state),
      event: create(EventSchema, {
        event: {
          case: 'started',
          value: create(StartedSchema, {
            tick: 42n,
          }),
        },
      }),
    }),
  );
}

function encodeStepResponse(): Uint8Array {
  return toBinary(
    StepResponseSchema,
    create(StepResponseSchema, {
      state: new Uint8Array([9, 8]),
      effects: [
        create(EffectSchema, {
          effect: { case: 'sendChat', value: create(SendChatSchema, { msg: 'hello' }) },
        }),
        create(EffectSchema, {
          effect: { case: 'emitBeep', value: create(EmitBeepSchema, { millis: 250 }) },
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
      started: {
        tick: 42,
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

Deno.test('Player program step accepts binary input and returns ProtoJSON output', async () => {
  let receivedBody: Uint8Array | undefined;
  const app = await createTestApp(
    () => Promise.resolve(activeProgram),
    async (request) => {
      receivedBody = new Uint8Array(await request.arrayBuffer());
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
      'application/json; charset=utf-8',
      'step should return JSON content',
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
    if (receivedBody === undefined) throw new Error('worker should receive the request body');
    const received = fromBinary(StepRequestSchema, receivedBody);
    assertEquals(received.executionId?.high, 1n, 'binary UUID high value should round trip');
    assertEquals(received.event?.event.case, 'started', 'binary event should round trip');
  } finally {
    await app.cleanup();
  }
});

Deno.test('Player program step accepts and returns ProtoJSON', async () => {
  let receivedBody: Uint8Array | undefined;
  const app = await createTestApp(
    () => Promise.resolve(activeProgram),
    async (request) => {
      receivedBody = new Uint8Array(await request.arrayBuffer());
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
    if (receivedBody === undefined) throw new Error('worker should receive the converted request');
    const request = fromBinary(StepRequestSchema, receivedBody);
    assertEquals(request.executionId?.high, 1n, 'ProtoJSON UUID high value should convert');
    assertEquals(request.event?.event.case, 'started', 'ProtoJSON oneof should convert');
  } finally {
    await app.cleanup();
  }
});

Deno.test('Player program step returns ProtoJSON without an Accept header', async () => {
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
      'application/json; charset=utf-8',
      'step should return JSON content',
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
      'response should be ProtoJSON',
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

Deno.test('Player program step enforces HTTP policy and validates binary requests', async () => {
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
      400,
      'malformed protobuf messages should be rejected',
    );
    if (runnerCalled) throw new Error('malformed requests must not reach the program runner');
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

Deno.test('Player program step returns a server error when the program fails', async () => {
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
    await assertJsonError(response, 500);
  } finally {
    await app.cleanup();
  }
});

Deno.test('Player program step fails when the program response has the wrong content type', async () => {
  const responseBody = new Uint8Array([1, 2, 3]);
  const app = await createTestApp(
    () => Promise.resolve(activeProgram),
    () => Promise.resolve(binaryResponse(responseBody, 200, 'text/plain')),
  );
  try {
    const cookie = await authenticatePlayerSession(app.handler);
    const response = await app.handler(stepRequest(encodeStepRequest(), cookie));
    await assertJsonError(response, 500);
  } finally {
    await app.cleanup();
  }
});

Deno.test('Player program step fails when the program response is malformed', async () => {
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
      500,
    );
  } finally {
    await app.cleanup();
  }
});

function encodeErrorResponse(error: string): Uint8Array {
  return toBinary(ErrorResponseSchema, create(ErrorResponseSchema, { error }));
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
