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
  let modStream: WebSocketConnection | undefined;

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
    modStream = await WebSocketConnection.open(
      `127.0.0.1:${port}`,
      '/modapi/v1/execution-stats',
      { authorization: `Bearer ${API_KEY}` },
    );
    await modStream.sendBinary(toBinary(Internal.ExecutionStatsTickSchema, report));

    const snapshot = fromBinary(
      Internal.ExecutionStatsTickSchema,
      await within(5_000, stream.readBinaryFrame(), 'execution-stats WebSocket frame'),
    );
    assertEquals(snapshot.sampledAtUnixMillis, report.sampledAtUnixMillis, 'sample time changed');
    assertEquals(snapshot.tickSequence, report.tickSequence, 'tick sequence changed');
    assertEquals(snapshot.players.length, 1, 'player stream exposed more than one player');

    const player = snapshot.players[0];
    assertUuidEquals(player.playerId, PLAYER_ID, 'received the wrong player statistics');
    assertEquals(player.usedBytes, 0n, 'a player without a User Worker should use no memory');
    assertEquals(player.maxBytes, 256n * 1024n * 1024n, 'memory quota changed');
    assertEquals(player.events[0].accepted, 4n, 'accepted-event count changed');
    assertEquals(player.events[0].dropped, 5n, 'dropped-event count changed');
    assertEquals(player.eventsPerSync[0].count, 2n, 'events-per-sync count changed');
    assertEquals(player.eventsPerSync[0].total, 3n, 'events-per-sync total changed');
    assertEquals(player.eventsPerSync[0].max, 2n, 'events-per-sync maximum changed');
    assertEquals(player.effects[0].attempted, 6n, 'effect-attempt count changed');
    assertEquals(player.effects[0].failed, 1n, 'failed-effect count changed');
    assertEquals(player.effects[0].totalNanos, 7n, 'effect-duration total changed');
    assertEquals(player.effects[0].maxNanos, 4n, 'effect-duration maximum changed');
    assertEquals(player.cobbleputerStates.length, 1, 'Cobbleputer state changed');
  } finally {
    duplicateStream?.close();
    modStream?.close();
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

function executionStatsReport(): Internal.ExecutionStatsTick {
  const cobbleputer = create(Internal.ExecutionStatsTick_CobbleputerRefSchema, {
    cobbleputerId: uuid(0n, 10n),
    position: create(Common.GlobalPosSchema, {
      dimension: create(Common.IdentifierSchema, { ns: 'minecraft', path: 'overworld' }),
      position: create(Common.Vec3iSchema, { x: 1, y: 64, z: 2 }),
    }),
  });
  return create(Internal.ExecutionStatsTickSchema, {
    sampledAtUnixMillis: 1_234_567_890n,
    tickSequence: 1n,
    players: [
      create(Internal.ExecutionStatsTick_PlayerStatsSchema, {
        playerId: PLAYER_ID,
        events: [create(Internal.ExecutionStatsTick_EventMeasurementSchema, {
          cobbleputer,
          type: Internal.InstrumentationEventType.CHAT_MESSAGE,
          accepted: 4n,
          dropped: 5n,
        })],
        eventsPerSync: [create(Internal.ExecutionStatsTick_EventsPerSyncMeasurementSchema, {
          cobbleputer,
          count: 2n,
          total: 3n,
          max: 2n,
        })],
        effects: [create(Internal.ExecutionStatsTick_EffectMeasurementSchema, {
          cobbleputer,
          type: Internal.InstrumentationEffectType.SEND_CHAT,
          attempted: 6n,
          failed: 1n,
          totalNanos: 7n,
          maxNanos: 4n,
        })],
        cobbleputerStates: [create(Internal.ExecutionStatsTick_CobbleputerStateSchema, {
          cobbleputer,
          state: Internal.InstrumentationLifecycleState.RUNNING,
        })],
      }),
      create(Internal.ExecutionStatsTick_PlayerStatsSchema, {
        playerId: OTHER_PLAYER_ID,
        events: [create(Internal.ExecutionStatsTick_EventMeasurementSchema, {
          cobbleputer,
          type: Internal.InstrumentationEventType.CHAT_MESSAGE,
          accepted: 100n,
        })],
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

  async sendBinary(payload: Uint8Array): Promise<void> {
    const mask = crypto.getRandomValues(new Uint8Array(4));
    let header: Uint8Array;
    if (payload.length < 126) {
      header = new Uint8Array([0x82, 0x80 | payload.length]);
    } else if (payload.length <= 0xffff) {
      header = new Uint8Array([0x82, 0xfe, payload.length >> 8, payload.length & 0xff]);
    } else {
      throw new Error('test WebSocket client does not support frames larger than 65535 bytes');
    }
    const frame = new Uint8Array(header.length + mask.length + payload.length);
    frame.set(header);
    frame.set(mask, header.length);
    for (let index = 0; index < payload.length; index += 1) {
      frame[header.length + mask.length + index] = payload[index] ^ mask[index % mask.length];
    }
    await this.connection.write(frame);
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
