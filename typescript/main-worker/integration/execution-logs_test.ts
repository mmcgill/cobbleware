import { Common, create, fromBinary, toBinary } from '@cobbleware/core';
import * as Internal from '@cobbleware/core/internal';

const API_KEY = 'execution-logs-integration-key';
const AUTH_CODE_ONE = 'LOGS0001';
const AUTH_CODE_TWO = 'LOGS0002';
const PLAYER_ONE_UUID = 'd2a99799-250c-4eff-a5ed-dc421fcd189c';
const PLAYER_TWO_UUID = '00000000-0000-0000-0000-000000000002';

Deno.test('Execution logs flow from an authenticated producer only to the owning Player', async () => {
  const gameDir = await Deno.makeTempDir({ prefix: 'cobbleware-execution-logs-integration-' });
  const port = await reservePort();
  const baseUrl = `http://127.0.0.1:${port}`;
  let runtime: Deno.ChildProcess | undefined;
  let firstPlayer: WebSocket | undefined;
  let secondPlayer: WebSocket | undefined;
  let producer: WebSocket | undefined;

  try {
    runtime = await startRuntime(port, gameDir);
    await waitForRuntime(baseUrl);
    const [firstCookie, secondCookie] = await Promise.all([
      authenticatePlayer(baseUrl, AUTH_CODE_ONE, PLAYER_ONE_UUID),
      authenticatePlayer(baseUrl, AUTH_CODE_TWO, PLAYER_TWO_UUID),
    ]);

    firstPlayer = await openWebSocket(`${webSocketUrl(baseUrl)}/playerapi/v1/logs`, {
      cookie: firstCookie,
      origin: baseUrl,
    });
    secondPlayer = await openWebSocket(`${webSocketUrl(baseUrl)}/playerapi/v1/logs`, {
      cookie: secondCookie,
      origin: baseUrl,
    });
    producer = await openWebSocket(`${webSocketUrl(baseUrl)}/modapi/v1/logs`, {
      authorization: `Bearer ${API_KEY}`,
    });

    const expected = entry(uuidFromString(PLAYER_ONE_UUID));
    const received = nextBinary(firstPlayer);
    producer.send(toBinary(Internal.ExecutionLogEntrySchema, expected));
    const actual = fromBinary(
      Internal.ExecutionLogEntrySchema,
      await within(
        5_000,
        received,
        'owner log frame',
      ),
    );
    assertEquals(actual.inner.case, 'log', 'wrong log entry type');
    if (actual.inner.case !== 'log') throw new Error('missing program log');
    assertEquals(actual.inner.value.msg, 'hello from the Event Worker', 'log text changed');
    await expectNoMessage(secondPlayer, 200);
  } finally {
    producer?.close();
    firstPlayer?.close();
    secondPlayer?.close();
    if (runtime !== undefined) {
      try {
        runtime.kill('SIGTERM');
      } catch {
        // The Runtime may already have exited; its status below is still collected.
      }
      await runtime.status;
    }
    await Deno.remove(gameDir, { recursive: true });
  }
});

async function startRuntime(port: number, gameDir: string): Promise<Deno.ChildProcess> {
  const runtimeBinary = Deno.env.get('EDGE_RUNTIME_BIN') ?? await Deno.realPath(
    new URL(
      Deno.build.os === 'windows'
        ? '../../../mc-edge-runtime/target/debug/edge-runtime.exe'
        : '../../../mc-edge-runtime/target/debug/edge-runtime',
      import.meta.url,
    ),
  );
  const repositoryDir = new URL('../../../', import.meta.url);
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
    cwd: repositoryDir,
    env: { COBBLEWARE_API_KEY: API_KEY, COBBLEWARE_GAME_DIR: gameDir },
    stdout: 'inherit',
    stderr: 'inherit',
  }).spawn();
}

async function authenticatePlayer(
  baseUrl: string,
  code: string,
  playerUUID: string,
): Promise<string> {
  const modResponse = await fetch(`${baseUrl}/modapi/v1/authentication`, {
    method: 'POST',
    headers: { authorization: `Bearer ${API_KEY}`, 'content-type': 'application/json' },
    body: JSON.stringify({ code, playerUUID, playerProfileName: `Logs-${code}` }),
  });
  assertEquals(modResponse.status, 201, `mod authentication failed: ${await modResponse.text()}`);
  const playerResponse = await fetch(`${baseUrl}/playerapi/v1/authenticate`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ code }),
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

async function openWebSocket(url: string, headers: HeadersInit): Promise<WebSocket> {
  const socket = new WebSocket(url, { headers });
  socket.binaryType = 'arraybuffer';
  await new Promise<void>((resolve, reject) => {
    socket.onopen = () => resolve();
    socket.onerror = () => reject(new Error(`WebSocket failed to connect: ${url}`));
  });
  return socket;
}

function nextBinary(socket: WebSocket): Promise<Uint8Array> {
  return new Promise((resolve, reject) => {
    socket.onmessage = (event) => {
      if (event.data instanceof ArrayBuffer) resolve(new Uint8Array(event.data));
      else reject(new Error('received a non-binary execution log'));
    };
    socket.onerror = () => reject(new Error('WebSocket failed while awaiting execution log'));
  });
}

async function expectNoMessage(socket: WebSocket, milliseconds: number): Promise<void> {
  await new Promise<void>((resolve, reject) => {
    const timeout = setTimeout(resolve, milliseconds);
    socket.onmessage = () => {
      clearTimeout(timeout);
      reject(new Error('another player received an execution log'));
    };
  });
}

async function reservePort(): Promise<number> {
  const listener = Deno.listen({ hostname: '127.0.0.1', port: 0 });
  try {
    return (listener.addr as Deno.NetAddr).port;
  } finally {
    listener.close();
  }
}

async function waitForRuntime(baseUrl: string): Promise<void> {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    try {
      if ((await fetch(`${baseUrl}/hello`)).status === 200) return;
    } catch {
      // The listener is not ready yet.
    }
    await delay(50);
  }
  throw new Error('Edge Runtime did not become ready within 10 seconds');
}

function entry(playerId: Common.Uuid): Internal.ExecutionLogEntry {
  return create(Internal.ExecutionLogEntrySchema, {
    playerId,
    inner: {
      case: 'log',
      value: create(Internal.ProgramLogEntrySchema, {
        level: Internal.LogLevel.INFO,
        msg: 'hello from the Event Worker',
      }),
    },
  });
}

function uuidFromString(value: string): Common.Uuid {
  const hex = value.replaceAll('-', '');
  return create(Common.UuidSchema, {
    high: BigInt(`0x${hex.slice(0, 16)}`),
    low: BigInt(`0x${hex.slice(16)}`),
  });
}

function webSocketUrl(httpUrl: string): string {
  return `ws${httpUrl.slice('http'.length)}`;
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function within<T>(
  milliseconds: number,
  promise: Promise<T>,
  description: string,
): Promise<T> {
  let timeout: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      promise,
      new Promise<T>((_, reject) => {
        timeout = setTimeout(
          () => reject(new Error(`timed out waiting for ${description}`)),
          milliseconds,
        );
      }),
    ]);
  } finally {
    if (timeout !== undefined) clearTimeout(timeout);
  }
}

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) throw new Error(`${message}: expected ${expected}, got ${actual}`);
}
