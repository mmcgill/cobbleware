import { Common, create, Effects, Events, fromBinary, Program, toBinary } from '@cobbleware/core';
import * as Internal from '@cobbleware/core/internal';
import { type AppConfig, configFromEnv } from '../src/config.ts';
import { DurableAuthStore } from '../src/auth/store.ts';
import { createHandler } from '../src/index.ts';
import type { ActiveProgram, ProgramStore } from '../src/programs/types.ts';
import type { ProgramRunner } from '../src/programs/start-states.ts';
import { MAX_EVENTS_PER_EXECUTION } from '../src/executions/manager.ts';

const API_KEY = 'test-api-key';
const PATH = 'http://localhost/modapi/v1/executions';
const PROGRAM_ID = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';

const activeProgram: ActiveProgram = {
  metadata: {
    id: PROGRAM_ID,
    name: 'test',
    uploadedAt: '2026-09-04T00:00:00.000Z',
    sizeBytes: 2,
  },
  content: new Uint8Array([7, 8]),
};

type TestApp = {
  handler: (request: Request) => Promise<Response>;
  cleanup: () => Promise<void>;
};

async function createTestApp(step: ProgramRunner['step']): Promise<TestApp> {
  const storageDir = await Deno.makeTempDir({ prefix: 'cobbleware-executions-test-' });
  const config: AppConfig = configFromEnv({ apiKey: API_KEY, storageDir });
  const authStore = await DurableAuthStore.open(config);
  const programStore: ProgramStore = {
    list: () => Promise.resolve({ programs: [], quotaBytes: 0, usedBytes: 0, remainingBytes: 0 }),
    readActive: () => Promise.resolve(activeProgram),
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

function execution(
  options: Partial<{
    executionId: Common.Uuid;
    playerId: Common.Uuid;
    initialState: Uint8Array;
    events: Events.Event[];
    eventSeqNum: bigint;
    effectSeqNum: bigint;
    state: Internal.ExecutionStatus;
    stateSizeLimit: bigint;
  }> = {},
): Internal.ExecutionState {
  return create(Internal.ExecutionStateSchema, {
    executionId: options.executionId ?? uuid(1n, 2n),
    playerId: options.playerId ?? uuid(3n, 4n),
    initialState: options.initialState ?? new Uint8Array([1]),
    events: options.events ?? [startedEvent(1n)],
    eventSeqNum: options.eventSeqNum ?? 1n,
    effectSeqNum: options.effectSeqNum ?? 0n,
    state: options.state ?? Internal.ExecutionStatus.RUNNING,
    stateSizeLimit: options.stateSizeLimit ?? 1024n,
  });
}

function request(
  executions: Internal.ExecutionState[],
  options: Partial<{ method: string; contentType: string; authorized: boolean; body: Uint8Array }> =
    {},
): Request {
  const headers = new Headers();
  if (options.authorized !== false) headers.set('authorization', `Bearer ${API_KEY}`);
  headers.set('content-type', options.contentType ?? 'application/octet-stream');
  const body = options.body ?? toBinary(
    Internal.ExecutionsRequestSchema,
    create(Internal.ExecutionsRequestSchema, { executions }),
  );
  const method = options.method ?? 'POST';
  return new Request(PATH, {
    method,
    headers,
    body: method === 'GET' || method === 'HEAD'
      ? undefined
      : new Blob([body.slice().buffer as ArrayBuffer]),
  });
}

function uuid(high: bigint, low: bigint): Common.Uuid {
  return create(Common.UuidSchema, { high, low });
}

function startedEvent(tick: bigint): Events.Event {
  return create(Events.EventSchema, {
    event: { case: 'started', value: create(Events.StartedSchema, { tick }) },
  });
}

function stepResponse(state: Uint8Array, effects: Effects.Effect[] = []): Response {
  const body = toBinary(
    Program.StepResponseSchema,
    create(Program.StepResponseSchema, { state, effects }),
  );
  return new Response(new Blob([body.slice().buffer as ArrayBuffer]), {
    status: 200,
    headers: { 'content-type': 'application/octet-stream' },
  });
}

async function decodeResponse(response: Response): Promise<Internal.ExecutionsResponse> {
  assertEquals(response.status, 200, 'execution sync should succeed');
  assertEquals(
    response.headers.get('content-type'),
    'application/octet-stream',
    'success should be protobuf binary',
  );
  return fromBinary(
    Internal.ExecutionsResponseSchema,
    new Uint8Array(await response.arrayBuffer()),
  );
}

async function assertError(response: Response, status: number): Promise<void> {
  assertEquals(response.status, status, 'unexpected error status');
  assertEquals(
    response.headers.get('content-type'),
    'application/octet-stream',
    'errors should be protobuf binary',
  );
  const body = fromBinary(
    Internal.ErrorResponseSchema,
    new Uint8Array(await response.arrayBuffer()),
  );
  if (body.error === '') throw new Error('error body must contain an error string');
}

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) {
    throw new Error(`${message}: expected ${String(expected)}, got ${String(actual)}`);
  }
}

async function flushTasks(): Promise<void> {
  for (let index = 0; index < 5; index += 1) {
    await Promise.resolve();
  }
}

Deno.test('Execution sync uses protobuf responses for request, authentication, and method errors', async () => {
  const app = await createTestApp(() => Promise.resolve(stepResponse(new Uint8Array([2]))));
  try {
    await assertError(await app.handler(request([], { authorized: false })), 401);
    await assertError(await app.handler(request([], { method: 'GET' })), 405);
    await assertError(await app.handler(request([], { contentType: 'application/json' })), 415);
    await assertError(await app.handler(request([], { body: new Uint8Array([0xff]) })), 400);
  } finally {
    await app.cleanup();
  }
});

Deno.test('Execution sync rejects an invalid snapshot without replacing cached work', async () => {
  let calls = 0;
  const app = await createTestApp(() => {
    calls += 1;
    return Promise.resolve(stepResponse(new Uint8Array([9])));
  });
  const valid = execution();
  try {
    await decodeResponse(await app.handler(request([valid])));
    await flushTasks();
    assertEquals(calls, 1, 'valid snapshot should dispatch one event');

    const duplicate = execution({ executionId: valid.executionId });
    await assertError(await app.handler(request([valid, duplicate])), 400);

    const result = await decodeResponse(await app.handler(request([valid])));
    assertEquals(result.results[0]?.eventSeqNum, 1n, 'cache should retain completed work');
    assertEquals(calls, 1, 'retry should not dispatch an already consumed event');
  } finally {
    await app.cleanup();
  }
});

Deno.test('Execution sync asynchronously advances state and acknowledges effect sequences', async () => {
  let resolveStep: ((response: Response) => void) | undefined;
  let receivedStep: Uint8Array | undefined;
  let receivedPlayer: string | undefined;
  const app = await createTestApp((_request, player, _program, body) => {
    receivedPlayer = player;
    receivedStep = body;
    return new Promise<Response>((resolve) => {
      resolveStep = resolve;
    });
  });
  const initial = execution();
  try {
    const first = await decodeResponse(await app.handler(request([initial])));
    assertEquals(
      first.results[0]?.eventSeqNum,
      0n,
      'initial response should not await the program',
    );
    assertEquals(first.results[0]?.effectSeqNum, 1n, 'first effect sequence should be one');
    await flushTasks();
    if (receivedStep === undefined || resolveStep === undefined) {
      throw new Error('program invocation was not started');
    }
    const step = fromBinary(Program.StepRequestSchema, receivedStep);
    assertEquals(step.executionId?.high, 1n, 'program should receive the execution id');
    assertEquals(step.event?.event.case, 'started', 'program should receive the queued event');
    assertEquals(step.effectLimit, 50, 'program should receive the available effect capacity');
    assertEquals(
      receivedPlayer,
      '00000000-0000-0003-0000-000000000004',
      'program lookup should use the canonical player UUID',
    );

    resolveStep(stepResponse(
      new Uint8Array([9]),
      [create(Effects.EffectSchema, {
        effect: { case: 'sendChat', value: create(Effects.SendChatSchema, { msg: 'hello' }) },
      })],
    ));
    await flushTasks();

    const completed = await decodeResponse(await app.handler(request([initial])));
    const result = completed.results[0]!;
    assertEquals(result.eventSeqNum, 1n, 'completion should consume the event');
    assertEquals(
      JSON.stringify([...result.finalState]),
      JSON.stringify([9]),
      'state should advance',
    );
    assertEquals(result.effectSeqNum, 1n, 'returned effect should start at one');
    assertEquals(result.effects.length, 1, 'effect should remain until acknowledged');

    const acknowledged = execution({
      initialState: new Uint8Array([9]),
      events: [],
      eventSeqNum: 2n,
      effectSeqNum: 1n,
    });
    const afterAcknowledgement = await decodeResponse(await app.handler(request([acknowledged])));
    const acknowledgedResult = afterAcknowledgement.results[0]!;
    assertEquals(acknowledgedResult.effects.length, 0, 'acknowledgement should remove the effect');
    assertEquals(
      acknowledgedResult.effectSeqNum,
      2n,
      'empty outbox should report the next sequence',
    );
  } finally {
    await app.cleanup();
  }
});

Deno.test('Execution sync crashes an execution when the program response is invalid', async () => {
  let calls = 0;
  const app = await createTestApp(() => {
    calls += 1;
    return Promise.resolve(
      new Response(new Uint8Array([1, 2]), {
        status: 200,
        headers: { 'content-type': 'text/plain' },
      }),
    );
  });
  const initial = execution({
    initialState: new Uint8Array([4]),
    events: [startedEvent(7n), startedEvent(8n)],
  });
  try {
    await decodeResponse(await app.handler(request([initial])));
    await flushTasks();
    const crashed = await decodeResponse(await app.handler(request([initial])));
    const result = crashed.results[0]!;
    assertEquals(
      result.state,
      Internal.ExecutionStatus.CRASHED,
      'invalid response should crash execution',
    );
    assertEquals(result.eventSeqNum, 1n, 'only the failing event should be consumed');
    assertEquals(
      JSON.stringify([...result.finalState]),
      JSON.stringify([4]),
      'state should not change',
    );
    assertEquals(calls, 1, 'crashed execution should not dispatch more events');
  } finally {
    await app.cleanup();
  }
});

Deno.test('Execution sync rejects an oversized final state before it reaches the Mod', async () => {
  const app = await createTestApp(() => Promise.resolve(stepResponse(new Uint8Array([1, 2, 3]))));
  const initial = execution({ initialState: new Uint8Array([4]), stateSizeLimit: 2n });
  try {
    await decodeResponse(await app.handler(request([initial])));
    await flushTasks();
    const response = await decodeResponse(await app.handler(request([initial])));
    const result = response.results[0]!;
    assertEquals(result.state, Internal.ExecutionStatus.CRASHED, 'oversized state should crash');
    assertEquals(
      result.failureReason,
      Internal.ExecutionFailureReason.STATE_SIZE_LIMIT,
      'crash should identify the state-size limit',
    );
    assertEquals(JSON.stringify([...result.finalState]), JSON.stringify([4]), 'old state should remain');
    assertEquals(result.effects.length, 0, 'oversized response effects should be discarded');
  } finally {
    await app.cleanup();
  }
});

Deno.test('Execution sync ignores a stale invocation after an authoritative re-sync', async () => {
  let resolveStep: ((response: Response) => void) | undefined;
  const app = await createTestApp(() =>
    new Promise<Response>((resolve) => {
      resolveStep = resolve;
    })
  );
  const initial = execution({ initialState: new Uint8Array([1]) });
  const replacement = execution({ initialState: new Uint8Array([7]), events: [] });
  try {
    await decodeResponse(await app.handler(request([initial])));
    await flushTasks();
    if (resolveStep === undefined) throw new Error('initial invocation was not started');

    const resynced = await decodeResponse(await app.handler(request([replacement])));
    assertEquals(
      JSON.stringify([...resynced.results[0]!.finalState]),
      JSON.stringify([7]),
      'authoritative state should replace the incompatible cache entry',
    );

    resolveStep(stepResponse(new Uint8Array([9])));
    await flushTasks();
    const afterStaleCompletion = await decodeResponse(await app.handler(request([replacement])));
    assertEquals(
      JSON.stringify([...afterStaleCompletion.results[0]!.finalState]),
      JSON.stringify([7]),
      'stale invocation must not overwrite the re-synced state',
    );
  } finally {
    await app.cleanup();
  }
});

Deno.test('Execution sync retains at most ten events and refills after the Mod accepts a prefix', async () => {
  const maxEvents = MAX_EVENTS_PER_EXECUTION;
  let calls = 0;
  const app = await createTestApp(() => {
    calls += 1;
    return Promise.resolve(stepResponse(new Uint8Array([maxEvents - 1])));
  });
  const firstBatch = execution({
    events: Array.from({ length: maxEvents + 1 }, (_, index) => startedEvent(BigInt(index + 1))),
  });
  try {
    let result: Internal.ExecutionResult | undefined;
    for (let attempt = 0; attempt < 100; attempt += 1) {
      result = (await decodeResponse(await app.handler(request([firstBatch])))).results[0];
      if (result?.eventSeqNum === BigInt(maxEvents)) break;
      await new Promise((resolve) => setTimeout(resolve, 0));
    }
    if (result === undefined || result.eventSeqNum !== BigInt(maxEvents)) {
      throw new Error('the first ten events did not complete');
    }
    assertEquals(calls, maxEvents, 'only the event window should be dispatched');

    const remainingEvent = execution({
      initialState: new Uint8Array([maxEvents-1]),
      events: [startedEvent(BigInt(maxEvents+1))],
      eventSeqNum: BigInt(maxEvents+1),
    });
    for (let attempt = 0; attempt < 100; attempt += 1) {
      result = (await decodeResponse(await app.handler(request([remainingEvent])))).results[0];
      if (result?.eventSeqNum === BigInt(maxEvents+1)) break;
      await new Promise((resolve) => setTimeout(resolve, 0));
    }
    assertEquals(result?.eventSeqNum, BigInt(maxEvents+1), 'the next sync should refill and dispatch event eleven');
    assertEquals(calls, maxEvents+1, 'the refilled event should dispatch once');
  } finally {
    await app.cleanup();
  }
});

Deno.test('Execution sync retains at most fifty unacknowledged effects', async () => {
  const effects = Array.from({ length: 51 }, (_, index) =>
    create(Effects.EffectSchema, {
      effect: {
        case: 'sendChat',
        value: create(Effects.SendChatSchema, { msg: `effect ${index}` }),
      },
    }));
  const app = await createTestApp(() =>
    Promise.resolve(stepResponse(new Uint8Array([9]), effects))
  );
  const initial = execution();
  try {
    await decodeResponse(await app.handler(request([initial])));
    await flushTasks();
    const retained = (await decodeResponse(await app.handler(request([initial])))).results[0]!;
    assertEquals(retained.effects.length, 50, 'only fifty effects should be retained');
    assertEquals(retained.effectSeqNum, 1n, 'retained effects should begin at sequence one');
    const lastEffect = retained.effects[49];
    if (lastEffect === undefined || lastEffect.effect.case !== 'sendChat') {
      throw new Error('the fiftieth retained effect should be SendChat');
    }
    assertEquals(
      lastEffect.effect.value.msg,
      'effect 49',
      'the earliest fifty effects should be retained in order',
    );

    const acknowledged = execution({
      initialState: new Uint8Array([9]),
      events: [],
      eventSeqNum: 2n,
      effectSeqNum: 50n,
    });
    const acknowledgementResponse = await decodeResponse(
      await app.handler(request([acknowledged])),
    );
    const afterAcknowledgement = acknowledgementResponse.results[0]!;
    assertEquals(afterAcknowledgement.effects.length, 0, 'acknowledgement should clear the outbox');
    assertEquals(
      afterAcknowledgement.effectSeqNum,
      51n,
      'dropped effects must not leave a sequence gap',
    );
  } finally {
    await app.cleanup();
  }
});

Deno.test('Execution sync advertises the remaining effect capacity for each step', async () => {
  const effect = create(Effects.EffectSchema, {
    effect: { case: 'emitBeep', value: create(Effects.EmitBeepSchema, {}) },
  });
  const effectLimits: number[] = [];
  let calls = 0;
  const app = await createTestApp((_request, _player, _program, body) => {
    const step = fromBinary(Program.StepRequestSchema, body);
    effectLimits.push(step.effectLimit);
    const effectCount = calls++ === 0 ? 49 : 2;
    return Promise.resolve(stepResponse(new Uint8Array([calls]), Array(effectCount).fill(effect)));
  });
  const initial = execution({ events: [startedEvent(1n), startedEvent(2n)] });
  try {
    let result: Internal.ExecutionResult | undefined;
    for (let attempt = 0; attempt < 100; attempt += 1) {
      result = (await decodeResponse(await app.handler(request([initial])))).results[0];
      if (result?.eventSeqNum === 2n) break;
      await new Promise((resolve) => setTimeout(resolve, 0));
    }

    assertEquals(result?.eventSeqNum, 2n, 'both events should complete');
    assertEquals(JSON.stringify(effectLimits), JSON.stringify([50, 1]), 'effect limits');
    assertEquals(result?.effects.length, 50, 'only the advertised capacity should be retained');
  } finally {
    await app.cleanup();
  }
});
