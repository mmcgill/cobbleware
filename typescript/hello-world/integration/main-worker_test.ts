import { Common, create, Events, fromBinary, toBinary } from '@cobbleware/core';
import * as Internal from '@cobbleware/core/internal';

const BASE_URL = 'http://localhost:9000';
const MOD_API_KEY = 'secret';
const PLAYER_UUID = '00000000-0000-0000-0000-000000000001';
const PLAYER_PROFILE_NAME = 'HelloWorldIntegration';
const AUTH_CODE = 'TEST0001';
const PROGRAM_ARCHIVE_PATH = new URL('../hello-world.eszip', import.meta.url);
const PLAYER_ID = uuid(0n, 1n);
const FIRST_EXECUTION_ID = uuid(11n, 101n);
const SECOND_EXECUTION_ID = uuid(22n, 202n);

type ProgramMetadata = {
  id: string;
};

Deno.test('Hello World runs through the Main Worker API', async () => {
  const cookie = await authenticatePlayer();
  const program = await uploadProgram(cookie);

  await assertStep(
    program.id,
    cookie,
    {
      executionId: { high: '1', low: '2' },
      state: 'AQID',
      event: {
        puterPlaced: {
          levelKey: { ns: 'minecraft', path: 'overworld' },
          pos: { x: 1, y: 2, z: 3 },
        },
      },
    },
    'AQID',
    [{ sendChat: { msg: 'Hello world' } }],
  );

  await assertStep(
    program.id,
    cookie,
    {
      executionId: { high: '1', low: '2' },
      state: 'BAU=',
      event: {
        puterReset: { tick: '123' },
      },
    },
    'BAU=',
    [{ emitBeep: {} }],
  );

  await assertExecutionSync();
});

async function assertExecutionSync(): Promise<void> {
  const first = executionState(FIRST_EXECUTION_ID, new Uint8Array([10]), [
    startedEvent(),
    resumedEvent(10n, 11n),
  ]);
  const second = executionState(SECOND_EXECUTION_ID, new Uint8Array([20]), [
    resumedEvent(20n, 22n),
    startedEvent(),
  ]);

  let completed: Internal.ExecutionsResponse | undefined;
  for (let attempt = 0; attempt < 100; attempt += 1) {
    const response = await syncExecutions([first, second]);
    if (response.results.every((result) => result.eventSeqNum === 2n)) {
      completed = response;
      break;
    }
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  if (completed === undefined) {
    throw new Error('executions did not complete within the integration-test deadline');
  }

  assertExecutionResult(
    resultFor(completed, FIRST_EXECUTION_ID),
    FIRST_EXECUTION_ID,
    [10],
    ['sendChat', 'emitBeep'],
  );
  assertExecutionResult(
    resultFor(completed, SECOND_EXECUTION_ID),
    SECOND_EXECUTION_ID,
    [20],
    ['emitBeep', 'sendChat'],
  );

  const acknowledged = await syncExecutions([
    executionState(FIRST_EXECUTION_ID, new Uint8Array([10]), [], 3n, 2n),
    executionState(SECOND_EXECUTION_ID, new Uint8Array([20]), [], 3n, 2n),
  ]);
  for (const result of acknowledged.results) {
    assertEquals(result.eventSeqNum, 2n, 'acknowledgement must retain the consumed event sequence');
    assertEquals(result.effects.length, 0, 'acknowledgement must remove all returned effects');
    assertEquals(
      result.effectSeqNum,
      3n,
      'empty effect outbox must report the next sequence number',
    );
  }
}

async function authenticatePlayer(): Promise<string> {
  const modResponse = await fetch(`${BASE_URL}/modapi/v1/authentication`, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${MOD_API_KEY}`,
      'content-type': 'application/json',
    },
    body: JSON.stringify({
      code: AUTH_CODE,
      playerUUID: PLAYER_UUID,
      playerProfileName: PLAYER_PROFILE_NAME,
    }),
  });
  await assertStatus(modResponse, [201], 'Mod authentication');

  const playerResponse = await fetch(`${BASE_URL}/playerapi/v1/authenticate`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ code: AUTH_CODE }),
  });
  await assertStatus(playerResponse, [201], 'Player authentication');

  const cookie = playerResponse.headers.get('set-cookie')?.split(';', 1)[0];
  if (cookie === undefined) {
    throw new Error('Player authentication did not set a session cookie');
  }
  return cookie;
}

async function uploadProgram(cookie: string): Promise<ProgramMetadata> {
  const archive = await Deno.readFile(PROGRAM_ARCHIVE_PATH);
  const form = new FormData();
  form.append(
    'program',
    new File([archive], 'hello-world.eszip', { type: 'application/octet-stream' }),
  );

  const response = await fetch(`${BASE_URL}/playerapi/v1/programs`, {
    method: 'POST',
    headers: {
      cookie,
      origin: BASE_URL,
    },
    body: form,
  });
  await assertStatus(response, [200, 201], 'Program upload');

  const metadata: unknown = await response.json();
  if (!isProgramMetadata(metadata)) {
    throw new Error(`Program upload returned invalid metadata: ${JSON.stringify(metadata)}`);
  }
  return metadata;
}

async function syncExecutions(
  executions: Internal.ExecutionState[],
): Promise<Internal.ExecutionsResponse> {
  const body = toBinary(
    Internal.ExecutionsRequestSchema,
    create(Internal.ExecutionsRequestSchema, { executions }),
  );
  const response = await fetch(`${BASE_URL}/modapi/v1/executions`, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${MOD_API_KEY}`,
      'content-type': 'application/octet-stream',
    },
    body: new Blob([body.slice().buffer as ArrayBuffer]),
  });
  await assertStatus(response, [200], 'Execution sync');
  if (response.headers.get('content-type') !== 'application/octet-stream') {
    throw new Error(
      `Execution sync returned unexpected content type ${response.headers.get('content-type')}`,
    );
  }
  return fromBinary(
    Internal.ExecutionsResponseSchema,
    new Uint8Array(await response.arrayBuffer()),
  );
}

function executionState(
  executionId: Common.Uuid,
  initialState: Uint8Array,
  events: Events.Event[],
  eventSeqNum = 1n,
  effectSeqNum = 0n,
): Internal.ExecutionState {
  return create(Internal.ExecutionStateSchema, {
    executionId,
    initialState,
    events,
    eventSeqNum,
    effectSeqNum,
    playerId: PLAYER_ID,
    state: Internal.ExecutionStatus.RUNNING,
  });
}

function startedEvent(): Events.Event {
  return create(Events.EventSchema, {
    event: {
      case: 'started',
      value: create(Events.StartedSchema, {
        tick: 42n,
      }),
    },
  });
}

function resumedEvent(stopTick: bigint, resumeTick: bigint): Events.Event {
  return create(Events.EventSchema, {
    event: { case: 'resumed', value: create(Events.ResumedSchema, { resumeTick, stopTick }) },
  });
}

function resultFor(
  response: Internal.ExecutionsResponse,
  executionId: Common.Uuid,
): Internal.ExecutionResult {
  const result = response.results.find((candidate) => sameUuid(candidate.executionId, executionId));
  if (result === undefined) {
    throw new Error(`missing result for execution ${executionId.high}:${executionId.low}`);
  }
  return result;
}

function assertExecutionResult(
  result: Internal.ExecutionResult,
  executionId: Common.Uuid,
  expectedState: number[],
  expectedEffects: string[],
): void {
  if (!sameUuid(result.executionId, executionId)) {
    throw new Error('execution result has the wrong execution id');
  }
  assertEquals(result.state, Internal.ExecutionStatus.RUNNING, 'execution must remain running');
  assertEquals(result.eventSeqNum, 2n, 'two events must be consumed');
  assertEquals(result.effectSeqNum, 1n, 'first effect sequence must be one');
  assertEquals(
    JSON.stringify([...result.finalState]),
    JSON.stringify(expectedState),
    'execution returned unexpected state',
  );
  assertEquals(
    JSON.stringify(result.effects.map((effect) => effect.effect.case)),
    JSON.stringify(expectedEffects),
    'execution took unexpected transition branches',
  );
}

function uuid(high: bigint, low: bigint): Common.Uuid {
  return create(Common.UuidSchema, { high, low });
}

function sameUuid(left: Common.Uuid | undefined, right: Common.Uuid): boolean {
  return left?.high === right.high && left.low === right.low;
}

async function assertStep(
  programId: string,
  cookie: string,
  request: unknown,
  expectedState: string,
  expectedEffects: unknown,
): Promise<void> {
  const response = await fetch(`${BASE_URL}/playerapi/v1/programs/${programId}/step`, {
    method: 'POST',
    headers: {
      accept: 'application/json',
      cookie,
      'content-type': 'application/json',
      origin: BASE_URL,
    },
    body: JSON.stringify(request),
  });
  await assertStatus(response, [200], 'Program step');

  const body: unknown = await response.json();
  console.log(body);
  if (!isStepResponse(body)) {
    throw new Error(`Program step returned invalid ProtoJSON: ${JSON.stringify(body)}`);
  }
  assertEquals(body.state, expectedState, 'Program step returned an unexpected state');
  assertEquals(
    JSON.stringify(body.effects),
    JSON.stringify(expectedEffects),
    'Program step returned unexpected effects',
  );
}

async function assertStatus(
  response: Response,
  expected: number[],
  operation: string,
): Promise<void> {
  if (expected.includes(response.status)) {
    return;
  }
  throw new Error(
    `${operation} returned ${response.status}; expected ${expected.join(' or ')}: ${await response
      .text()}`,
  );
}

function isProgramMetadata(value: unknown): value is ProgramMetadata {
  return isRecord(value) && typeof value.id === 'string';
}

function isStepResponse(value: unknown): value is { state: string; effects: unknown } {
  return isRecord(value) && typeof value.state === 'string' && 'effects' in value;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) {
    throw new Error(`${message}: expected ${String(expected)}, got ${String(actual)}`);
  }
}
