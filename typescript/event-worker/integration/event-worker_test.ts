import { fromBinary } from '@cobbleware/core';
import { ExecutionLogEntrySchema } from '@cobbleware/core/internal';

const API_KEY = 'event-worker-integration-key';
const AUTH_CODE = 'EVENT001';
const PLAYER_UUID = 'd2a99799-250c-4eff-a5ed-dc421fcd189c';
const LOG_MESSAGE = 'event-worker-end-to-end-log';

Deno.test('Event Worker relays a User Worker console log to its Player', async () => {
  const gameDir = await Deno.makeTempDir({ prefix: 'cobbleware-event-worker-game-' });
  const programDir = await Deno.makeTempDir({
    dir: await Deno.realPath(new URL('../', import.meta.url)),
    prefix: '.cobbleware-event-worker-program-',
  });
  const port = await reservePort();
  const baseUrl = `http://127.0.0.1:${port}`;
  let runtime: Deno.ChildProcess | undefined;
  let stream: WebSocket | undefined;

  try {
    const program = await bundleLoggingProgram(programDir);
    runtime = await startRuntime(port, gameDir, baseUrl);
    await waitForRuntime(baseUrl);
    const cookie = await authenticatePlayer(baseUrl);
    stream = await openWebSocket(`${webSocketUrl(baseUrl)}/playerapi/v1/logs`, {
      cookie,
      origin: baseUrl,
    });

    // The Event Worker starts before the Main Worker and reconnects asynchronously.
    await delay(500);
    await uploadProgram(baseUrl, cookie, program);
    const received = nextBinary(stream);
    const query = await fetch(`${baseUrl}/modapi/v1/players/${PLAYER_UUID}/program`, {
      headers: { authorization: `Bearer ${API_KEY}` },
    });
    const queryBody = await query.text();
    if (query.status !== 200) throw new Error(`program query failed: ${queryBody}`);
    if (!queryBody.includes('programError')) {
      throw new Error(`test program unexpectedly returned start states: ${queryBody}`);
    }

    // A failed initial connection is intentionally best-effort. The first event
    // causes the relay to replace that stale connection now that the Main Worker is live.
    await delay(250);
    const secondQuery = await fetch(`${baseUrl}/modapi/v1/players/${PLAYER_UUID}/program`, {
      headers: { authorization: `Bearer ${API_KEY}` },
    });
    if (secondQuery.status !== 200) {
      throw new Error(`second program query failed: ${await secondQuery.text()}`);
    }

    const entry = fromBinary(
      ExecutionLogEntrySchema,
      await within(5_000, received, 'Event Worker execution log'),
    );
    assertEquals(entry.inner.case, 'log', 'Event Worker did not relay a program log');
    if (entry.inner.case !== 'log') throw new Error('wrong log entry case');
    assertEquals(entry.inner.value.msg, `${LOG_MESSAGE}\n`, 'Event Worker changed the program log');
  } finally {
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
    await Deno.remove(programDir, { recursive: true });
  }
});

async function bundleLoggingProgram(programDir: string): Promise<Uint8Array> {
  await Deno.writeTextFile(
    `${programDir}/index.ts`,
    `Deno.serve(() => { console.log(${
      JSON.stringify(LOG_MESSAGE)
    }); return new Response('not a program response'); });`,
  );
  const command = new Deno.Command(await runtimeBinary(), {
    args: ['bundle', '--entrypoint', 'index.ts', '--output', 'program.eszip'],
    cwd: programDir,
    stdout: 'inherit',
    stderr: 'inherit',
  });
  const result = await command.output();
  if (!result.success) throw new Error(`program bundle exited with status ${result.code}`);
  return await Deno.readFile(`${programDir}/program.eszip`);
}

async function startRuntime(
  port: number,
  gameDir: string,
  mainWorkerUrl: string,
): Promise<Deno.ChildProcess> {
  const repositoryDir = new URL('../../../', import.meta.url);
  return new Deno.Command(await runtimeBinary(), {
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
      '--event-worker',
      'typescript/event-worker/event-worker.eszip',
      '--events-entrypoint',
      'file:///src/index.ts',
    ],
    cwd: repositoryDir,
    env: {
      COBBLEWARE_API_KEY: API_KEY,
      COBBLEWARE_GAME_DIR: gameDir,
      COBBLEWARE_MAIN_WORKER_URL: mainWorkerUrl,
    },
    stdout: 'inherit',
    stderr: 'inherit',
  }).spawn();
}

async function runtimeBinary(): Promise<string> {
  return Deno.env.get('EDGE_RUNTIME_BIN') ?? await Deno.realPath(
    new URL(
      Deno.build.os === 'windows'
        ? '../../../mc-edge-runtime/target/debug/edge-runtime.exe'
        : '../../../mc-edge-runtime/target/debug/edge-runtime',
      import.meta.url,
    ),
  );
}

async function authenticatePlayer(baseUrl: string): Promise<string> {
  const modResponse = await fetch(`${baseUrl}/modapi/v1/authentication`, {
    method: 'POST',
    headers: { authorization: `Bearer ${API_KEY}`, 'content-type': 'application/json' },
    body: JSON.stringify({
      code: AUTH_CODE,
      playerUUID: PLAYER_UUID,
      playerProfileName: 'EventWorkerIntegration',
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

async function uploadProgram(baseUrl: string, cookie: string, content: Uint8Array): Promise<void> {
  const form = new FormData();
  form.append(
    'program',
    new File([content.slice().buffer as ArrayBuffer], 'event-worker.eszip', {
      type: 'application/octet-stream',
    }),
  );
  const response = await fetch(`${baseUrl}/playerapi/v1/programs`, {
    method: 'POST',
    headers: { cookie, origin: baseUrl },
    body: form,
  });
  if (response.status !== 201) throw new Error(`program upload failed: ${await response.text()}`);
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
