import {
  Common,
  create,
  createProgramApiHandler,
  Effects,
  Events,
  fromBinary,
  Program,
  type Program as EventProgram,
  toBinary,
} from '../src/mod.ts';

const binaryContentType = 'application/octet-stream';

Deno.test('Program API returns configured start states', async () => {
  const response = await handler(new Request('http://localhost/startStates'));

  assertEquals(response.status, 200, 'start states should succeed');
  assertEquals(
    response.headers.get('content-type'),
    binaryContentType,
    'start states content type',
  );
  const decoded = fromBinary(
    Program.StartStatesResponseSchema,
    new Uint8Array(await response.arrayBuffer()),
  );
  assertEquals(
    JSON.stringify(decoded.startStates.map(({ stateName, state }) => [stateName, [...state]])),
    JSON.stringify([['one', [1]], ['two', [2]]]),
    'start states should round-trip',
  );
});

Deno.test('Program API decodes a step and encodes its result', async () => {
  const request = create(Program.StepRequestSchema, {
    executionId: create(Common.UuidSchema, { high: 1n, low: 2n }),
    state: new Uint8Array([3]),
    event: create(Events.EventSchema, {
      event: {
        case: 'resumed',
        value: create(Events.ResumedSchema, { stopTick: 3n, resumeTick: 4n }),
      },
    }),
  });
  const response = await handler(
    binaryRequest('/nextState', toBinary(Program.StepRequestSchema, request)),
  );

  assertEquals(response.status, 200, 'step should succeed');
  const decoded = fromBinary(
    Program.StepResponseSchema,
    new Uint8Array(await response.arrayBuffer()),
  );
  assertEquals(
    JSON.stringify([...decoded.state]),
    JSON.stringify([4]),
    'handler should receive state',
  );
  assertEquals(decoded.effects[0]?.effect.case, 'emitBeep', 'handler effects should round-trip');
});

Deno.test('Program API delivers a BlockStateChange to the event handler', async () => {
  let received: Events.BlockStateChange | undefined;
  const blockStateHandler = createProgramApiHandler({
    startStates: {},
    program: (state, event) => {
      if (event.event.case !== 'blockStateChange') throw new Error('unexpected event');
      received = event.event.value;
      return create(Program.StepResponseSchema, { state, effects: [] });
    },
  });
  const request = create(Program.StepRequestSchema, {
    state: new Uint8Array([3]),
    event: create(Events.EventSchema, {
      event: {
        case: 'blockStateChange',
        value: create(Events.BlockStateChangeSchema, {
          observerColor: Common.DyeColor.RED,
          observerDir: Common.Direction.EAST,
          observerPos: globalPos(1, 64, 1),
          blockPos: globalPos(2, 64, 1),
          oldState: blockInfo('redstone_lamp', { lit: 'false' }),
          newState: blockInfo('redstone_lamp', { lit: 'true' }),
        }),
      },
    }),
  });

  const response = await blockStateHandler(
    binaryRequest('/nextState', toBinary(Program.StepRequestSchema, request)),
  );

  assertEquals(response.status, 200, 'block-state step should succeed');
  if (received === undefined) throw new Error('handler did not receive the BlockStateChange');
  assertEquals(received.observerColor, Common.DyeColor.RED, 'observer color should reach handler');
  assertEquals(received.newState?.properties.lit, 'true', 'new state should reach handler');
});

Deno.test('Program API returns a binary ErrorResponse for invalid and failing steps', async () => {
  const malformed = await handler(binaryRequest('/nextState', new Uint8Array([1, 2, 3])));
  await assertErrorResponse(malformed);

  const throwingHandler = createProgramApiHandler({
    startStates: {},
    program: () => {
      throw new Error('program exploded');
    },
  });
  const request = create(Program.StepRequestSchema, {
    event: create(Events.EventSchema, {
      event: {
        case: 'resumed',
        value: create(Events.ResumedSchema, { stopTick: 12n, resumeTick: 15n }),
      },
    }),
  });
  const failed = await throwingHandler(
    binaryRequest('/nextState', toBinary(Program.StepRequestSchema, request)),
  );
  await assertErrorResponse(failed, 'program exploded');
});

Deno.test('Program API rejects unsupported routes and methods', async () => {
  const missing = await handler(new Request('http://localhost/missing'));
  assertEquals(missing.status, 404, 'unknown routes should return 404');

  const startStatesMethod = await handler(
    new Request('http://localhost/startStates', { method: 'POST' }),
  );
  assertEquals(startStatesMethod.status, 405, 'start states only supports GET');
  assertEquals(startStatesMethod.headers.get('allow'), 'GET', 'start states Allow header');

  const nextStateMethod = await handler(new Request('http://localhost/nextState'));
  assertEquals(nextStateMethod.status, 405, 'next state only supports POST');
  assertEquals(nextStateMethod.headers.get('allow'), 'POST', 'next state Allow header');
});

const handle: EventProgram = (state, event) => {
  if (event.event.case !== 'resumed') throw new Error('unexpected event');
  return create(Program.StepResponseSchema, {
    state: new Uint8Array([state[0]! + 1]),
    effects: [
      create(Effects.EffectSchema, {
        effect: { case: 'emitBeep', value: create(Effects.EmitBeepSchema, {}) },
      }),
    ],
  });
};

const handler = createProgramApiHandler({
  program: handle,
  startStates: {
    one: new Uint8Array([1]),
    two: new Uint8Array([2]),
  },
});

function binaryRequest(path: string, body: Uint8Array): Request {
  return new Request(`http://localhost${path}`, {
    method: 'POST',
    headers: { 'content-type': binaryContentType },
    body: new Blob([body.slice().buffer as ArrayBuffer]),
  });
}

function globalPos(x: number, y: number, z: number): Common.GlobalPos {
  return create(Common.GlobalPosSchema, {
    dimension: create(Common.IdentifierSchema, { ns: 'minecraft', path: 'overworld' }),
    position: create(Common.Vec3iSchema, { x, y, z }),
  });
}

function blockInfo(path: string, properties: Record<string, string>): Events.BlockInfo {
  return create(Events.BlockInfoSchema, {
    blockType: create(Common.IdentifierSchema, { ns: 'minecraft', path }),
    properties,
  });
}

async function assertErrorResponse(response: Response, expectedError?: string): Promise<void> {
  assertEquals(response.status, 500, 'step failure status');
  assertEquals(
    response.headers.get('content-type'),
    binaryContentType,
    'step failure content type',
  );
  const decoded = fromBinary(
    Program.ErrorResponseSchema,
    new Uint8Array(await response.arrayBuffer()),
  );
  if (expectedError !== undefined) {
    assertEquals(decoded.error, expectedError, 'error response message');
  }
}

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) {
    throw new Error(`${message}: expected ${String(expected)}, got ${String(actual)}`);
  }
}
