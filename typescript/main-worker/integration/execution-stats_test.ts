import { Common, create, fromBinary, toBinary } from '@cobbleware/core';
import * as Internal from '@cobbleware/core/internal';

const API_KEY = 'execution-stats-integration-key';
const AUTH_CODE = 'STAT0001';
const PLAYER_UUID = 'd2a99799-250c-4eff-a5ed-dc421fcd189c';
// Both halves have their high bit set, exercising the unsigned UUID representation.
const PLAYER_ID = uuid(0xd2a99799250c4effn, 0xa5eddc421fcd189cn);
const OTHER_PLAYER_ID = uuid(0n, 2n);

Deno.test('Execution statistics flow from the Mod API to a Player WebSocket', async () => {
  const gameDir = await Deno.makeTempDir({ prefix: 'cobbleware-execution-stats-integration-' });
  const port = await reservePort();
  const baseUrl = `http://127.0.0.1:${port}`;
  let runtime: Deno.ChildProcess | undefined;
  let stream: WebSocketConnection | undefined;
  let duplicateStream: WebSocketConnection | undefined;

  try {
    runtime = await startRuntime(port, gameDir);
    await waitForRuntime(baseUrl);

    const cookie = await authenticatePlayer(baseUrl);
    stream = await WebSocketConnection.open(
      `127.0.0.1:${port}`,
      '/playerapi/v1/execution-stats',
      {
        cookie,
        origin: baseUrl,
      },
    );
    // The HTTP upgrade has completed, but the server-side WebSocket open event is dispatched separately.
    await delay(50);

    duplicateStream = await WebSocketConnection.open(
      `127.0.0.1:${port}`,
      '/playerapi/v1/execution-stats',
      {
        cookie,
        origin: baseUrl,
      },
    );
    const duplicateError = JSON.parse(
      new TextDecoder().decode(
        await within(5_000, duplicateStream.readTextFrame(), 'duplicate-stream error message'),
      ),
    );
    assertEquals(duplicateError.code, 'execution-stats-stream-already-open', 'wrong error code');
    assertEquals(
      duplicateError.error,
      'Execution statistics are already open in another browser window.',
      'wrong duplicate-stream error message',
    );
    assertEquals(
      await within(5_000, duplicateStream.readCloseCode(), 'duplicate-stream close frame'),
      1008,
      'duplicate stream was not rejected with a policy-violation close',
    );

    const report = executionStatsReport();
    const response = await fetch(`${baseUrl}/modapi/v1/execution-stats`, {
      method: 'POST',
      headers: {
        authorization: `Bearer ${API_KEY}`,
        'content-type': 'application/octet-stream',
      },
      body: toBinary(Internal.ExecutionStatsSchema, report),
    });
    assertEquals(response.status, 204, `stats report was rejected: ${await response.text()}`);

    const snapshot = fromBinary(
      Internal.ExecutionStatsSchema,
      await within(5_000, stream.readBinaryFrame(), 'execution-stats WebSocket frame'),
    );
    assertEquals(snapshot.sampledAtUnixMillis, report.sampledAtUnixMillis, 'sample time changed');
    assertEquals(snapshot.windowTicks, report.windowTicks, 'window length changed');
    assertEquals(snapshot.retainedTickCount, report.retainedTickCount, 'retained ticks changed');
    assertEquals(snapshot.players.length, 1, 'player stream exposed more than one player');

    const player = snapshot.players[0];
    assertUuidEquals(player.playerId, PLAYER_ID, 'received the wrong player statistics');
    assertEquals(player.eventsPerSyncCount, 2n, 'events-per-sync count changed');
    assertEquals(player.eventsPerSyncTotal, 3n, 'events-per-sync total changed');
    assertEquals(player.eventsPerSyncMax, 2n, 'events-per-sync maximum changed');
    assertEquals(player.eventsAccepted, 4n, 'accepted-event count changed');
    assertEquals(player.eventsDropped, 5n, 'dropped-event count changed');
    assertEquals(player.effectsAttempted, 6n, 'effect-attempt count changed');
    assertEquals(player.effectsFailed, 1n, 'failed-effect count changed');
    assertEquals(player.effectsTotalNanos, 7n, 'effect-duration total changed');
    assertEquals(player.effectsMaxNanos, 4n, 'effect-duration maximum changed');
    assertEquals(player.runningCobbleputers, 1, 'running-cobbleputer count changed');
    assertEquals(player.stoppedCobbleputers, 2, 'stopped-cobbleputer count changed');
  } finally {
    duplicateStream?.close();
    stream?.close();
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
    env: {
      COBBLEWARE_API_KEY: API_KEY,
      COBBLEWARE_GAME_DIR: gameDir,
    },
    stdout: 'inherit',
    stderr: 'inherit',
  }).spawn();
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
      const response = await fetch(`${baseUrl}/hello`);
      if (response.status === 200) return;
    } catch {
      // The listener is not ready yet.
    }
    await delay(50);
  }
  throw new Error('Edge Runtime did not become ready within 10 seconds');
}

async function authenticatePlayer(baseUrl: string): Promise<string> {
  const modResponse = await fetch(`${baseUrl}/modapi/v1/authentication`, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${API_KEY}`,
      'content-type': 'application/json',
    },
    body: JSON.stringify({
      code: AUTH_CODE,
      playerUUID: PLAYER_UUID,
      playerProfileName: 'ExecutionStatsIntegration',
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

function executionStatsReport(): Internal.ExecutionStats {
  return create(Internal.ExecutionStatsSchema, {
    sampledAtUnixMillis: 1_234_567_890n,
    windowTicks: 600,
    retainedTickCount: 20,
    players: [
      create(Internal.ExecutionStats_PlayerStatsSchema, {
        playerId: PLAYER_ID,
        eventsPerSyncCount: 2n,
        eventsPerSyncTotal: 3n,
        eventsPerSyncMax: 2n,
        eventsAccepted: 4n,
        eventsDropped: 5n,
        effectsAttempted: 6n,
        effectsFailed: 1n,
        effectsTotalNanos: 7n,
        effectsMaxNanos: 4n,
        runningCobbleputers: 1,
        stoppedCobbleputers: 2,
      }),
      create(Internal.ExecutionStats_PlayerStatsSchema, {
        playerId: OTHER_PLAYER_ID,
        eventsPerSyncCount: 1n,
        eventsPerSyncTotal: 1n,
        eventsPerSyncMax: 1n,
        eventsAccepted: 100n,
        effectsAttempted: 100n,
      }),
    ],
  });
}

class WebSocketConnection {
  private readonly reader: ReadableStreamDefaultReader<Uint8Array>;
  private buffered = new Uint8Array();

  private constructor(private readonly connection: Deno.TcpConn) {
    this.reader = connection.readable.getReader();
  }

  static async open(
    address: string,
    path: string,
    headers: Record<string, string>,
  ): Promise<WebSocketConnection> {
    const [hostname, portText] = address.split(':');
    const connection = await Deno.connect({ hostname, port: Number(portText) });
    const stream = new WebSocketConnection(connection);
    const key = btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(16))));
    const requestHeaders = [
      `GET ${path} HTTP/1.1`,
      `Host: ${address}`,
      'Connection: Upgrade',
      'Upgrade: websocket',
      `Sec-WebSocket-Key: ${key}`,
      'Sec-WebSocket-Version: 13',
      ...Object.entries(headers).map(([name, value]) => `${name}: ${value}`),
      '',
      '',
    ];
    await connection.write(new TextEncoder().encode(requestHeaders.join('\r\n')));

    const responseHeaders = await stream.readUpgradeHeaders();
    if (!responseHeaders.startsWith('HTTP/1.1 101 ')) {
      stream.close();
      throw new Error(`WebSocket upgrade failed: ${responseHeaders.trim()}`);
    }
    return stream;
  }

  async readBinaryFrame(): Promise<Uint8Array> {
    while (true) {
      const { opcode, payload } = await this.readFrame();
      if (opcode === 2) return payload;
      if (opcode === 8) throw new Error('server closed the WebSocket before sending statistics');
      if (opcode !== 9 && opcode !== 10) throw new Error(`unexpected WebSocket opcode ${opcode}`);
    }
  }

  async readTextFrame(): Promise<Uint8Array> {
    while (true) {
      const { opcode, payload } = await this.readFrame();
      if (opcode === 1) return payload;
      if (opcode === 8) {
        throw new Error('server closed the WebSocket before sending an error message');
      }
      if (opcode !== 9 && opcode !== 10) throw new Error(`unexpected WebSocket opcode ${opcode}`);
    }
  }

  async readCloseCode(): Promise<number> {
    while (true) {
      const { opcode, payload } = await this.readFrame();
      if (opcode === 8) {
        if (payload.length < 2) throw new Error('server close frame did not include a status code');
        return payload[0] * 256 + payload[1];
      }
      if (opcode !== 9 && opcode !== 10) throw new Error(`unexpected WebSocket opcode ${opcode}`);
    }
  }

  close(): void {
    this.reader.cancel().catch(() => undefined);
    try {
      this.connection.close();
    } catch (error) {
      if (!(error instanceof Deno.errors.BadResource)) throw error;
    }
  }

  private async readUpgradeHeaders(): Promise<string> {
    return new TextDecoder().decode(
      await within(5_000, this.readHttpHeaders(), 'WebSocket upgrade response'),
    );
  }

  private async readFrame(): Promise<{ opcode: number; payload: Uint8Array }> {
    const header = await this.readExactly(2);
    const final = (header[0] & 0x80) !== 0;
    const opcode = header[0] & 0x0f;
    if (!final) {
      throw new Error('fragmented WebSocket frames are not supported by this test client');
    }
    if ((header[1] & 0x80) !== 0) throw new Error('server WebSocket frames must not be masked');

    let length = header[1] & 0x7f;
    if (length === 126) {
      const extended = await this.readExactly(2);
      length = extended[0] * 256 + extended[1];
    } else if (length === 127) {
      const extended = await this.readExactly(8);
      let extendedLength = 0n;
      for (const byte of extended) extendedLength = extendedLength * 256n + BigInt(byte);
      if (extendedLength > BigInt(Number.MAX_SAFE_INTEGER)) {
        throw new Error('WebSocket frame is too large');
      }
      length = Number(extendedLength);
    }
    return { opcode, payload: await this.readExactly(length) };
  }

  private async readHttpHeaders(): Promise<Uint8Array> {
    while (true) {
      const headerEnd = indexOf(this.buffered, new Uint8Array([13, 10, 13, 10]));
      if (headerEnd >= 0) {
        const headers = this.buffered.slice(0, headerEnd + 4);
        this.buffered = this.buffered.slice(headerEnd + 4);
        return headers;
      }
      await this.readMore();
    }
  }

  private async readExactly(length: number): Promise<Uint8Array> {
    while (this.buffered.length < length) await this.readMore();
    const value = this.buffered.slice(0, length);
    this.buffered = this.buffered.slice(length);
    return value;
  }

  private async readMore(): Promise<void> {
    const { value, done } = await this.reader.read();
    if (done || value === undefined) throw new Error('WebSocket connection closed unexpectedly');
    const next = new Uint8Array(this.buffered.length + value.length);
    next.set(this.buffered);
    next.set(value, this.buffered.length);
    this.buffered = next;
  }
}

function uuid(high: bigint, low: bigint): Common.Uuid {
  return create(Common.UuidSchema, { high, low });
}

function assertUuidEquals(
  actual: Common.Uuid | undefined,
  expected: Common.Uuid,
  message: string,
): void {
  if (actual?.high !== expected.high || actual.low !== expected.low) {
    throw new Error(message);
  }
}

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) {
    throw new Error(`${message}: expected ${String(expected)}, got ${String(actual)}`);
  }
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

function indexOf(bytes: Uint8Array, needle: Uint8Array): number {
  for (let start = 0; start <= bytes.length - needle.length; start += 1) {
    let matches = true;
    for (let offset = 0; offset < needle.length; offset += 1) {
      if (bytes[start + offset] !== needle[offset]) {
        matches = false;
        break;
      }
    }
    if (matches) return start;
  }
  return -1;
}
