import { Common, create, Effects, Events, fromBinary, toBinary } from '@cobbleware/core';
import * as Internal from '@cobbleware/core/internal';

const API_KEY = 'execution-sync-integration-key';
const AUTH_CODE = 'SYNC0001';
const PLAYER_UUID = 'd2a99799-250c-4eff-a5ed-dc421fcd189c';
const PLAYER_ID = uuidFromString(PLAYER_UUID);
const EXECUTION_ID = uuid(1n, 2n);

Deno.test('Execution sync runs uploaded programs in a real durable-store user worker', async () => {
  const gameDir = await Deno.makeTempDir({ prefix: 'cobbleware-execution-sync-integration-' });
  const port = await reservePort();
  const baseUrl = `http://127.0.0.1:${port}`;
  let runtime: Deno.ChildProcess | undefined;

  try {
    runtime = await startRuntime(port, gameDir);
    await waitForRuntime(baseUrl);
    const cookie = await authenticatePlayer(baseUrl);
    await uploadExamplesProgram(baseUrl, cookie);

    const started = executionSnapshot(1n, new Uint8Array([1]), [startedEvent(1n)]);
    const first = await syncUntilConsumed(baseUrl, started, 1n);
    assertBytes(first.finalState, [1], 'Started step changed the hello state');
    assertEquals(first.effects.length, 1, 'Started step did not produce its chat effect');
    assertEffect(first.effects[0], 'sendChat', 'Started step returned the wrong effect type');
    if (first.effects[0]?.effect.case === 'sendChat') {
      assertEquals(
        first.effects[0].effect.value.msg,
        'Hello world',
        'Started step changed its chat effect',
      );
    }

    const resumed = executionSnapshot(2n, new Uint8Array([1]), [resumedEvent(1n, 2n)]);
    const second = await syncUntilConsumed(baseUrl, resumed, 2n);
    assertBytes(second.finalState, [1], 'Resumed step changed the hello state');
    assertEquals(second.effects.length, 1, 'Resumed step did not produce its beep effect');
    assertEffect(second.effects[0], 'emitBeep', 'Resumed step returned the wrong effect type');
  } finally {
    if (runtime !== undefined) {
      try {
        runtime.kill('SIGTERM');
      } catch {
        // The runtime may already have exited; waiting for its status still reaps it.
      }
      await runtime.status;
    }
    await Deno.remove(gameDir, { recursive: true });
  }
});

function executionSnapshot(
  eventSeqNum: bigint,
  initialState: Uint8Array,
  events: Events.Event[],
): Internal.ExecutionsRequest {
  return create(Internal.ExecutionsRequestSchema, {
    players: [create(Internal.PlayerExecutionStateSchema, {
      playerId: PLAYER_ID,
      executions: [create(Internal.ExecutionStateSchema, {
        executionId: EXECUTION_ID,
        initialState,
        events,
        eventSeqNum,
        stateSizeLimit: 1024n,
        outboxLimit: 10,
      })],
      outstandingEffectLimit: 20,
    })],
    workerLimit: 1,
  });
}

async function syncUntilConsumed(
  baseUrl: string,
  snapshot: Internal.ExecutionsRequest,
  eventSeqNum: bigint,
): Promise<Internal.ExecutionResult> {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    const response = await fetch(`${baseUrl}/modapi/v1/executions`, {
      method: 'POST',
      headers: { authorization: `Bearer ${API_KEY}`, 'content-type': 'application/octet-stream' },
      body: toBinary(Internal.ExecutionsRequestSchema, snapshot),
    });
    if (response.status !== 200) {
      throw new Error(`execution sync failed: HTTP ${response.status} ${await response.text()}`);
    }
    const decoded = fromBinary(
      Internal.ExecutionsResponseSchema,
      new Uint8Array(await response.arrayBuffer()),
    );
    const result = decoded.results[0];
    if (result === undefined) throw new Error('execution sync returned no result');
    if (result.executionFailed) {
      throw new Error(`program step failed with reason ${result.failureReason}`);
    }
    if (result.eventSeqNum === eventSeqNum) return result;
    await delay(25);
  }
  throw new Error(`execution did not consume event ${eventSeqNum} within ten seconds`);
}

async function uploadExamplesProgram(baseUrl: string, cookie: string): Promise<void> {
  const content = await Deno.readFile(new URL('../../examples/examples.eszip', import.meta.url));
  const form = new FormData();
  form.append(
    'program',
    new File([content], 'examples.eszip', { type: 'application/octet-stream' }),
  );
  const response = await fetch(`${baseUrl}/playerapi/v1/programs`, {
    method: 'POST',
    headers: { cookie, origin: baseUrl },
    body: form,
  });
  assertEquals(response.status, 201, `program upload failed: ${await response.text()}`);
}

async function authenticatePlayer(baseUrl: string): Promise<string> {
  const modResponse = await fetch(`${baseUrl}/modapi/v1/authentication`, {
    method: 'POST',
    headers: { authorization: `Bearer ${API_KEY}`, 'content-type': 'application/json' },
    body: JSON.stringify({
      code: AUTH_CODE,
      playerUUID: PLAYER_UUID,
      playerProfileName: 'ExecutionSyncIntegration',
    }),
  });
  assertEquals(modResponse.status, 201, `mod authentication failed: ${await modResponse.text()}`);

  const playerResponse = await fetch(`${baseUrl}/playerapi/v1/authenticate`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ code: AUTH_CODE }),
  });
  assertEquals(
    playerResponse.status,
    201,
    `player authentication failed: ${await playerResponse.text()}`,
  );
  const cookie = playerResponse.headers.get('set-cookie')?.split(';', 1)[0];
  if (cookie === undefined) {
    throw new Error('player authentication did not return a session cookie');
  }
  return cookie;
}

async function startRuntime(port: number, gameDir: string): Promise<Deno.ChildProcess> {
  const runtimeBinary = Deno.env.get('EDGE_RUNTIME_BIN') ?? await Deno.realPath(
    new URL(
      Deno.build.os === 'windows'
        ? '../../../mc-edge-runtime/target/debug/edge-runtime.exe'
        : '../../../mc-edge-runtime/target/debug/edge-runtime',
      import.meta.url,
    ),
  );
  return new Deno.Command(runtimeBinary, {
    args: [
      'start',
      '--ip',
      '127.0.0.1',
      '--port',
      String(port),
      '--main-service',
      'typescript/main-worker/main-worker.eszip',
      '--main-entrypoint',
      'file:///src/index.ts',
    ],
    cwd: new URL('../../../', import.meta.url),
    env: { COBBLEWARE_API_KEY: API_KEY, COBBLEWARE_GAME_DIR: gameDir },
    stdout: 'inherit',
    stderr: 'inherit',
  }).spawn();
}

async function waitForRuntime(baseUrl: string): Promise<void> {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    try {
      if ((await fetch(`${baseUrl}/hello`)).status === 200) return;
    } catch {
      // The listener is not ready yet.
    }
    await delay(25);
  }
  throw new Error('runtime did not start within ten seconds');
}

async function reservePort(): Promise<number> {
  const listener = Deno.listen({ hostname: '127.0.0.1', port: 0 });
  try {
    return (listener.addr as Deno.NetAddr).port;
  } finally {
    listener.close();
  }
}

function startedEvent(tick: bigint): Events.Event {
  return create(Events.EventSchema, {
    event: { case: 'started', value: create(Events.StartedSchema, { tick }) },
  });
}

function resumedEvent(stopTick: bigint, resumeTick: bigint): Events.Event {
  return create(Events.EventSchema, {
    event: { case: 'resumed', value: create(Events.ResumedSchema, { stopTick, resumeTick }) },
  });
}

function uuid(high: bigint, low: bigint): Common.Uuid {
  return create(Common.UuidSchema, { high, low });
}

function uuidFromString(value: string): Common.Uuid {
  const hex = value.replaceAll('-', '');
  return uuid(BigInt(`0x${hex.slice(0, 16)}`), BigInt(`0x${hex.slice(16)}`));
}

function assertEffect(
  value: Effects.Effect | undefined,
  expected: Effects.Effect['effect']['case'],
  message: string,
): void {
  assertEquals(value?.effect.case, expected, message);
}

function assertBytes(actual: Uint8Array, expected: number[], message: string): void {
  assertEquals(JSON.stringify([...actual]), JSON.stringify(expected), message);
}

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) {
    throw new Error(`${message}: expected ${String(expected)}, got ${String(actual)}`);
  }
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
