const BASE_URL = 'http://localhost:9000';
const MOD_API_KEY = 'secret';
const PLAYER_UUID = '00000000-0000-0000-0000-000000000001';
const PLAYER_PROFILE_NAME = 'HelloWorldIntegration';
const AUTH_CODE = 'TEST0001';
const PROGRAM_ARCHIVE_PATH = new URL('../hello-world.eszip', import.meta.url);

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
      puterId: { high: '1', low: '2' },
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
      puterId: { high: '1', low: '2' },
      state: 'BAU=',
      event: {
        puterReset: { tick: '123' },
      },
    },
    'BAU=',
    [{ emitBeep: {} }],
  );
});

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
