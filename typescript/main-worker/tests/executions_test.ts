import { Common, create, Effects, Events, fromBinary, Program, toBinary } from '@cobbleware/core';
import * as Internal from '@cobbleware/core/internal';
import { configFromEnv } from '../src/config.ts';
import { EXECUTIONS_CONTENT_TYPE, handleExecutions } from '../src/executions/handlers.ts';
import { ExecutionManager, ExecutionRequestError } from '../src/executions/manager.ts';
import type { ActiveProgram, ProgramStore } from '../src/programs/types.ts';

const PROGRAM_A = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';
const PROGRAM_B = 'BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB';
const PLAYER_A = uuid(1n, 1n);
const PLAYER_B = uuid(2n, 2n);
const EXECUTION_A = uuid(3n, 3n);
const EXECUTION_B = uuid(4n, 4n);

class Deferred<T> {
  readonly promise: Promise<T>;
  private resolvePromise!: (value: T) => void;
  private rejectPromise!: (reason?: unknown) => void;

  constructor() {
    this.promise = new Promise((resolve, reject) => {
      this.resolvePromise = resolve;
      this.rejectPromise = reject;
    });
  }

  resolve(value: T): void {
    this.resolvePromise(value);
  }

  reject(reason: unknown): void {
    this.rejectPromise(reason);
  }
}

class TestWorker implements CobblewareUserWorker {
  private readonly closedDeferred = new Deferred<CobblewareUserWorkerClosed>();
  readonly closed = this.closedDeferred.promise;
  readonly requests: Program.StepRequest[] = [];
  readonly responses: Deferred<Response>[] = [];
  retireCalls = 0;

  retire(): void {
    this.retireCalls += 1;
  }

  async fetch(request: Request): Promise<Response> {
    const step = fromBinary(
      Program.StepRequestSchema,
      new Uint8Array(await request.arrayBuffer()),
    );
    this.requests.push(step);
    const response = new Deferred<Response>();
    this.responses.push(response);
    return await response.promise;
  }

  complete(index: number, response: Response): void {
    this.responses[index]?.resolve(response);
  }

  fail(index: number, error: Error): void {
    this.responses[index]?.reject(error);
  }

  close(): void {
    this.closedDeferred.resolve({});
  }
}

class TestProgramStore implements ProgramStore {
  readonly created: Array<{ playerId: string; worker: TestWorker }> = [];
  readonly active = new Map<string, ActiveProgram | null>();
  readonly availableWorkers: TestWorker[] = [];
  readonly pendingRuns: Deferred<{ program: ActiveProgram; worker: CobblewareUserWorker }>[] = [];
  activeReadCalls = 0;

  list(): Promise<never> {
    throw new Error('not used by execution tests');
  }

  readActive(playerId: string): Promise<ActiveProgram | null> {
    this.activeReadCalls += 1;
    return Promise.resolve(
      this.active.has(playerId) ? this.active.get(playerId)! : program(PROGRAM_A),
    );
  }

  upload(): Promise<never> {
    throw new Error('not used by execution tests');
  }

  run(playerId: string): Promise<{ program: ActiveProgram; worker: CobblewareUserWorker }> {
    const activeProgram = this.active.has(playerId)
      ? this.active.get(playerId)!
      : program(PROGRAM_A);
    if (activeProgram === null) {
      return Promise.reject(new Error('player has no active program'));
    }
    const worker = this.availableWorkers.shift() ?? new TestWorker();
    this.created.push({ playerId, worker });
    const pendingRun = this.pendingRuns.shift();
    if (pendingRun !== undefined) {
      return pendingRun.promise;
    }
    return Promise.resolve({ program: activeProgram, worker });
  }
}

function program(id: string): ActiveProgram {
  return {
    metadata: { id, name: 'test', uploadedAt: '2026-10-02T00:00:00.000Z', sizeBytes: 1 },
    content: new Uint8Array([1]),
  };
}

function execution(
  options: Partial<{
    executionId: Common.Uuid;
    initialState: Uint8Array;
    events: Events.Event[];
    eventSeqNum: bigint;
    stateSizeLimit: bigint;
    outboxLimit: number;
  }> = {},
): Internal.ExecutionState {
  return create(Internal.ExecutionStateSchema, {
    executionId: options.executionId ?? EXECUTION_A,
    initialState: options.initialState ?? new Uint8Array([1]),
    events: options.events ?? [event(1n)],
    eventSeqNum: options.eventSeqNum ?? 1n,
    stateSizeLimit: options.stateSizeLimit ?? 1024n,
    outboxLimit: options.outboxLimit ?? 10,
  });
}

function player(
  playerId: Common.Uuid,
  executions: Internal.ExecutionState[],
  outstandingEffectLimit = 20,
  totalExecutionStateLimit = 1024n,
): Internal.PlayerExecutionState {
  return create(Internal.PlayerExecutionStateSchema, {
    playerId,
    executions,
    outstandingEffectLimit,
    totalExecutionStateLimit,
  });
}

function request(
  players: Internal.PlayerExecutionState[],
  workerLimit = 4,
): Internal.ExecutionsRequest {
  return create(Internal.ExecutionsRequestSchema, { players, workerLimit });
}

function event(tick: bigint): Events.Event {
  return create(Events.EventSchema, {
    event: { case: 'started', value: create(Events.StartedSchema, { tick }) },
  });
}

function effect(message: string): Effects.Effect {
  return create(Effects.EffectSchema, {
    effect: { case: 'sendChat', value: create(Effects.SendChatSchema, { msg: message }) },
  });
}

function stepResponse(state: Uint8Array, effects: Effects.Effect[] = []): Response {
  return new Response(
    toBinary(
      Program.StepResponseSchema,
      create(Program.StepResponseSchema, {
        state,
        effects,
      }),
    ),
    { headers: { 'content-type': 'application/octet-stream' } },
  );
}

async function waitForRequests(worker: TestWorker, count: number): Promise<void> {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    if (worker.requests.length >= count) return;
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  throw new Error(`expected ${count} worker requests, got ${worker.requests.length}`);
}

async function settle(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
  await new Promise((resolve) => setTimeout(resolve, 0));
}

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) {
    throw new Error(`${message}: expected ${String(expected)}, got ${String(actual)}`);
  }
}

function assertBytes(actual: Uint8Array, expected: number[], message: string): void {
  assertEquals(JSON.stringify([...actual]), JSON.stringify(expected), message);
}

Deno.test('Execution sync is immediate and asynchronously records states and effects', async () => {
  const store = new TestProgramStore();
  const manager = new ExecutionManager(store, 1024);
  const snapshot = request([player(PLAYER_A, [execution()])]);

  const first = await manager.sync(snapshot);
  assertEquals(first.results[0]?.eventSeqNum, 0n, 'initial result should consume no events');
  const worker = store.created[0]!.worker;
  await waitForRequests(worker, 1);
  assertEquals(worker.requests[0]?.effectLimit, 10, 'step receives the execution outbox limit');
  worker.complete(0, stepResponse(new Uint8Array([9]), [effect('hello')]));
  await settle();

  const completed = await manager.sync(snapshot);
  const result = completed.results[0]!;
  assertEquals(result.eventSeqNum, 1n, 'completed event should be consumed');
  assertBytes(result.finalState, [9], 'completed state');
  assertEquals(result.effects.length, 1, 'completed effects');
});

Deno.test('Execution sync endpoint keeps protobuf success and error responses', async () => {
  const store = new TestProgramStore();
  const manager = new ExecutionManager(store, 1024);
  const config = configFromEnv({ apiKey: 'test-key', storageDir: 'unused' });
  const validBody = toBinary(Internal.ExecutionsRequestSchema, request([], 0));
  const success = await handleExecutions(
    new Request('http://localhost/modapi/v1/executions', {
      method: 'POST',
      headers: { authorization: 'Bearer test-key', 'content-type': EXECUTIONS_CONTENT_TYPE },
      body: validBody,
    }),
    config,
    manager,
  );
  assertEquals(success.status, 200, 'valid protobuf request succeeds');
  assertEquals(success.headers.get('content-type'), EXECUTIONS_CONTENT_TYPE, 'success is protobuf');

  const unauthorized = await handleExecutions(
    new Request('http://localhost/modapi/v1/executions', {
      method: 'POST',
      headers: { 'content-type': EXECUTIONS_CONTENT_TYPE },
      body: validBody,
    }),
    config,
    manager,
  );
  assertEquals(unauthorized.status, 401, 'missing bearer token is rejected');
  const error = fromBinary(
    Internal.ErrorResponseSchema,
    new Uint8Array(await unauthorized.arrayBuffer()),
  );
  assertEquals(error.error === '', false, 'error body is protobuf with a message');
});

Deno.test('Execution sync validates snapshots before changing cached work', async () => {
  const store = new TestProgramStore();
  const manager = new ExecutionManager(store, 1024);
  const valid = execution();
  await manager.sync(request([player(PLAYER_A, [valid])]));
  const worker = store.created[0]!.worker;
  await waitForRequests(worker, 1);

  let rejected = false;
  try {
    await manager.sync(request([player(PLAYER_A, [valid, execution()])]));
  } catch (error) {
    rejected = error instanceof ExecutionRequestError;
  }
  assertEquals(rejected, true, 'duplicated execution id should be rejected');
  worker.complete(0, stepResponse(new Uint8Array([2])));
  await settle();
  assertEquals(
    (await manager.sync(request([player(PLAYER_A, [valid])]))).results[0]?.eventSeqNum,
    1n,
    'rejected request must not replace the valid in-flight window',
  );
});

Deno.test('Execution sync requires a positive total execution-state limit for each player', async () => {
  const manager = new ExecutionManager(new TestProgramStore(), 1024);
  const missing = create(Internal.PlayerExecutionStateSchema, {
    playerId: PLAYER_A,
    executions: [execution()],
    outstandingEffectLimit: 20,
  });
  const zero = player(PLAYER_A, [execution()], 20, 0n);

  for (const invalid of [missing, zero]) {
    let rejected = false;
    try {
      await manager.sync(request([invalid]));
    } catch (error) {
      rejected = error instanceof ExecutionRequestError;
    }
    assertEquals(rejected, true, 'invalid total execution-state limit is rejected');
  }
});

Deno.test('Execution sync drops a window when an in-window state SHA-1 differs', async () => {
  const store = new TestProgramStore();
  const manager = new ExecutionManager(store, 1024);
  const original = execution({ initialState: new Uint8Array([1]) });
  await manager.sync(request([player(PLAYER_A, [original])]));
  const worker = store.created[0]!.worker;
  await waitForRequests(worker, 1);

  const replacement = execution({ initialState: new Uint8Array([7]) });
  const resynced = await manager.sync(request([player(PLAYER_A, [replacement])]));
  assertBytes(resynced.results[0]!.finalState, [7], 'authoritative replacement state');
  worker.complete(0, stepResponse(new Uint8Array([9])));
  await settle();
  const afterStale = await manager.sync(request([player(PLAYER_A, [replacement])]));
  assertBytes(afterStale.results[0]!.finalState, [7], 'stale invocation must not win');
});

Deno.test('Execution sync advances matching windows and extends them with all new events', async () => {
  const store = new TestProgramStore();
  const manager = new ExecutionManager(store, 1024);
  const initial = execution({ events: [event(1n), event(2n)] });
  await manager.sync(request([player(PLAYER_A, [initial])]));
  const worker = store.created[0]!.worker;
  await waitForRequests(worker, 1);
  worker.complete(0, stepResponse(new Uint8Array([2]), [effect('one')]));
  await waitForRequests(worker, 2);

  const advanced = execution({
    initialState: new Uint8Array([2]),
    eventSeqNum: 2n,
    events: [event(2n), event(3n)],
  });
  const response = await manager.sync(request([player(PLAYER_A, [advanced])]));
  assertEquals(response.results[0]?.effects.length, 0, 'advanced prefix effects are acknowledged');
  worker.complete(1, stepResponse(new Uint8Array([3])));
  await waitForRequests(worker, 3);
  const dispatched = worker.requests[2]?.event?.event;
  assertEquals(dispatched?.case, 'started', 'extended event is dispatched');
  if (dispatched?.case !== 'started') throw new Error('extended event should be Started');
  assertEquals(dispatched.value.tick, 3n, 'all new events are appended');
});

Deno.test('Execution sync failure consumes one event and resets after advancement', async () => {
  const store = new TestProgramStore();
  const manager = new ExecutionManager(store, 1024);
  const initial = execution({ events: [event(1n), event(2n)] });
  await manager.sync(request([player(PLAYER_A, [initial])]));
  const worker = store.created[0]!.worker;
  await waitForRequests(worker, 1);
  worker.complete(0, new Response('bad', { headers: { 'content-type': 'text/plain' } }));
  await settle();

  const failed = await manager.sync(request([player(PLAYER_A, [initial])]));
  assertEquals(failed.results[0]?.eventSeqNum, 1n, 'failed event is consumed');
  assertEquals(failed.results[0]?.executionFailed, true, 'failure is reported');
  assertEquals(worker.requests.length, 1, 'failed execution does not take another step');

  const advanced = execution({
    initialState: new Uint8Array([1]),
    eventSeqNum: 2n,
    events: [event(2n)],
  });
  const recovered = await manager.sync(request([player(PLAYER_A, [advanced])]));
  assertEquals(recovered.results[0]?.executionFailed, false, 'advancing clears failure');
});

Deno.test('A retired worker leaves its rejected event pending for a replacement worker', async () => {
  const store = new TestProgramStore();
  const manager = new ExecutionManager(store, 1024);
  const snapshot = request([player(PLAYER_A, [execution()])], 1);
  await manager.sync(snapshot);
  const retired = store.created[0]!.worker;
  await waitForRequests(retired, 1);

  const error = new Error('request cannot be handled because the worker has already retired');
  error.name = 'WorkerAlreadyRetired';
  retired.fail(0, error);
  await settle();

  const pending = await manager.sync(snapshot);
  assertEquals(pending.results[0]?.eventSeqNum, 0n, 'retired worker does not consume the event');
  assertEquals(
    pending.results[0]?.executionFailed,
    false,
    'retired worker does not fail the execution',
  );
  assertEquals(store.created.length, 1, 'retired worker holds its slot until it closes');

  retired.close();
  await settle();
  await manager.sync(snapshot);
  const replacement = store.created[1]!.worker;
  await waitForRequests(replacement, 1);
  replacement.complete(0, stepResponse(new Uint8Array([9])));
  await settle();

  const completed = await manager.sync(snapshot);
  assertEquals(completed.results[0]?.eventSeqNum, 1n, 'replacement worker retries the event');
  assertBytes(completed.results[0]!.finalState, [9], 'replacement state is applied');
});

Deno.test('Execution sync rejects an oversized program state before returning it', async () => {
  const store = new TestProgramStore();
  const manager = new ExecutionManager(store, 1024);
  const snapshot = execution({ initialState: new Uint8Array([1]), stateSizeLimit: 1n });
  await manager.sync(request([player(PLAYER_A, [snapshot])]));
  const worker = store.created[0]!.worker;
  await waitForRequests(worker, 1);
  worker.complete(0, stepResponse(new Uint8Array([2, 3])));
  await settle();

  const result = (await manager.sync(request([player(PLAYER_A, [snapshot])]))).results[0]!;
  assertEquals(result.executionFailed, true, 'oversized state fails the execution');
  assertEquals(
    result.failureReason,
    Internal.ExecutionFailureReason.STATE_SIZE_LIMIT,
    'oversized state identifies its failure reason',
  );
  assertBytes(result.finalState, [1], 'oversized state does not replace the current state');
});

Deno.test('Execution sync reports a program response larger than the total-state response limit', async () => {
  const store = new TestProgramStore();
  const manager = new ExecutionManager(store, 1024);
  const snapshot = request([player(PLAYER_A, [execution()], 20, 1n)]);
  await manager.sync(snapshot);
  const worker = store.created[0]!.worker;
  await waitForRequests(worker, 1);
  worker.complete(0, stepResponse(new Uint8Array([2])));
  await settle();

  const result = (await manager.sync(snapshot)).results[0]!;
  assertEquals(result.executionFailed, true, 'oversized response fails the execution');
  assertEquals(
    result.failureReason,
    Internal.ExecutionFailureReason.STEP_RESPONSE_SIZE_LIMIT,
    'oversized response identifies its failure reason',
  );
  assertBytes(result.finalState, [1], 'oversized response does not replace the current state');
  assertEquals(result.effects.length, 0, 'oversized response does not retain effects');
});

Deno.test('Execution sync observes the per-execution soft outbox threshold', async () => {
  const store = new TestProgramStore();
  const manager = new ExecutionManager(store, 1024);
  const snapshot = execution({ events: [event(1n), event(2n), event(3n)], outboxLimit: 1 });
  await manager.sync(request([player(PLAYER_A, [snapshot])]));
  const worker = store.created[0]!.worker;
  await waitForRequests(worker, 1);
  worker.complete(0, stepResponse(new Uint8Array([2]), [effect('one')]));
  await waitForRequests(worker, 2);
  worker.complete(1, stepResponse(new Uint8Array([3]), [effect('two')]));
  await settle();
  await manager.sync(request([player(PLAYER_A, [snapshot])]));
  assertEquals(worker.requests.length, 2, 'more-than-limit outbox stops later steps');
});

Deno.test('Execution sync observes the per-player soft outstanding-effect threshold', async () => {
  const store = new TestProgramStore();
  const manager = new ExecutionManager(store, 1024);
  const first = execution({ executionId: EXECUTION_A, outboxLimit: 2 });
  const second = execution({ executionId: EXECUTION_B, outboxLimit: 2 });
  await manager.sync(request([player(PLAYER_A, [first, second], 1)]));
  const worker = store.created[0]!.worker;
  await waitForRequests(worker, 1);
  worker.complete(0, stepResponse(new Uint8Array([2]), [effect('one'), effect('two')]));
  await settle();
  await manager.sync(request([player(PLAYER_A, [first, second], 1)]));
  assertEquals(worker.requests.length, 1, 'player total above limit blocks other executions');
});

Deno.test('Active program changes retire workers and invalidate windows out of band', async () => {
  const store = new TestProgramStore();
  const manager = new ExecutionManager(store, 1024);
  const snapshot = request([player(PLAYER_A, [execution()])]);
  await manager.sync(snapshot);
  const worker = store.created[0]!.worker;
  await waitForRequests(worker, 1);
  assertEquals(store.activeReadCalls, 0, 'execution manager does not read the active program');

  await manager.sync(snapshot);
  assertEquals(store.activeReadCalls, 0, 'sync does not reread an active worker program');

  store.active.set(uuidString(PLAYER_A), program(PROGRAM_B));
  await manager.activeProgramChanged(uuidString(PLAYER_A), PROGRAM_B);
  assertEquals(worker.retireCalls, 1, 'program change retires worker');
  worker.complete(0, stepResponse(new Uint8Array([9])));
  await settle();
  const invalidated = await manager.sync(snapshot);
  assertBytes(
    invalidated.results[0]!.finalState,
    [1],
    'invalidated invocation cannot update state',
  );

  worker.close();
  await settle();
  await manager.sync(snapshot);
  assertEquals(store.created.length, 2, 'next sync starts a replacement worker');

  const replacement = store.created[1]!.worker;
  await manager.sync(request([]));
  assertEquals(replacement.retireCalls, 1, 'absent player retires worker');
});

Deno.test('Initializing workers reserve capacity without holding the manager lock', async () => {
  const store = new TestProgramStore();
  const manager = new ExecutionManager(store, 1024);
  const startup = new Deferred<{ program: ActiveProgram; worker: CobblewareUserWorker }>();
  store.pendingRuns.push(startup);
  const first = request([player(PLAYER_A, [execution()])], 1);

  await manager.sync(first);
  await settle();
  const initializingWorker = store.created[0]!.worker;

  await manager.activeProgramChanged(uuidString(PLAYER_A), PROGRAM_B);
  await manager.sync(request([
    player(PLAYER_A, [execution()]),
    player(PLAYER_B, [execution({ executionId: EXECUTION_B })]),
  ], 1));
  assertEquals(store.created.length, 1, 'initializing worker occupies the worker limit');

  startup.resolve({ program: program(PROGRAM_A), worker: initializingWorker });
  await settle();
  assertEquals(initializingWorker.retireCalls, 1, 'retired initialization closes its worker');
  initializingWorker.close();
  await settle();

  await manager.sync(request([player(PLAYER_B, [execution({ executionId: EXECUTION_B })])], 1));
  assertEquals(store.created.length, 2, 'closed initialization frees capacity for another player');
});

Deno.test('Execution sync counts retired workers until their closed promise resolves', async () => {
  const store = new TestProgramStore();
  const manager = new ExecutionManager(store, 1024);
  const first = request([player(PLAYER_A, [execution()])], 1);
  await manager.sync(first);
  const firstWorker = store.created[0]!.worker;
  await waitForRequests(firstWorker, 1);
  store.active.set(uuidString(PLAYER_A), program(PROGRAM_B));
  await manager.sync(first);

  const secondPlayer = request([
    player(PLAYER_A, [execution()]),
    player(PLAYER_B, [execution({ executionId: EXECUTION_B })]),
  ], 1);
  await manager.sync(secondPlayer);
  assertEquals(store.created.length, 1, 'retired worker still occupies the worker limit');
  firstWorker.close();
  await settle();
  await manager.sync(secondPlayer);
  assertEquals(store.created.length, 2, 'closed retired worker frees the worker limit');
});

Deno.test('Execution sync does not create a worker once the requested worker limit is reached', async () => {
  const store = new TestProgramStore();
  const manager = new ExecutionManager(store, 1024);
  await manager.sync(request([player(PLAYER_A, [execution()])], 1));
  const worker = store.created[0]!.worker;
  await waitForRequests(worker, 1);

  await manager.sync(request([
    player(PLAYER_A, [execution()]),
    player(PLAYER_B, [execution({ executionId: EXECUTION_B })]),
  ], 1));
  assertEquals(store.created.length, 1, 'active worker occupies the requested worker limit');
});

Deno.test('Execution sync drops windows missing from the authoritative request and preserves result order', async () => {
  const store = new TestProgramStore();
  const manager = new ExecutionManager(store, 1024);
  const first = execution({ executionId: EXECUTION_A });
  const second = execution({ executionId: EXECUTION_B });
  const combined = request([player(PLAYER_B, [second]), player(PLAYER_A, [first])]);
  const response = await manager.sync(combined);
  assertEquals(
    response.results[0]?.executionId?.high,
    EXECUTION_B.high,
    'player order is preserved',
  );
  assertEquals(
    response.results[1]?.executionId?.high,
    EXECUTION_A.high,
    'execution order is preserved',
  );
  await manager.sync(request([player(PLAYER_A, [first])]));
  const reintroduced = await manager.sync(request([player(PLAYER_B, [second])]));
  assertEquals(
    reintroduced.results[0]?.eventSeqNum,
    0n,
    'omitted window is recreated from scratch',
  );
});

function uuid(high: bigint, low: bigint): Common.Uuid {
  return create(Common.UuidSchema, { high, low });
}

function uuidString(uuid: Common.Uuid): string {
  const value = uuid.high.toString(16).padStart(16, '0') + uuid.low.toString(16).padStart(16, '0');
  return `${value.slice(0, 8)}-${value.slice(8, 12)}-${value.slice(12, 16)}-${
    value.slice(16, 20)
  }-${value.slice(20)}`;
}
