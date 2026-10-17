import { create, createProgramApiHandler, fromBinary, type Program, toBinary } from '../src/mod.ts';
import {
  Direction,
  DyeColor,
  type GlobalPos,
  GlobalPosSchema,
  IdentifierSchema,
  type Uuid,
  UuidSchema,
  Vec3iSchema,
} from '../generated/common_pb.ts';
import { EffectSchema, EmitBeepSchema, SetRedstonePowerSchema } from '../generated/effects_pb.ts';
import {
  type BlockInfo,
  BlockInfoSchema,
  type BlockStateChange,
  BlockStateChangeSchema,
  type Event,
  EventSchema,
  ResumedSchema,
  WirelessObserverPairedSchema,
  WirelessObserverUnpairedSchema,
} from '../generated/events_pb.ts';
import {
  ErrorResponseSchema,
  StartStatesResponseSchema,
  StepRequestSchema,
  StepResponseSchema,
} from '../generated/program_pb.ts';

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
    StartStatesResponseSchema,
    new Uint8Array(await response.arrayBuffer()),
  );
  assertEquals(
    JSON.stringify(decoded.startStates.map(({ stateName, state }) => [stateName, [...state]])),
    JSON.stringify([['one', [1]], ['two', [2]]]),
    'start states should round-trip',
  );
});

Deno.test('Program API decodes a step and encodes its result', async () => {
  const request = create(StepRequestSchema, {
    executionId: create(UuidSchema, { high: 1n, low: 2n }),
    state: new Uint8Array([3]),
    event: create(EventSchema, {
      event: {
        case: 'resumed',
        value: create(ResumedSchema, { stopTick: 3n, resumeTick: 4n }),
      },
    }),
    effectLimit: 7,
    stateSizeLimit: 42n,
    burstChatTokens: 3,
    slowChatTokens: 87,
  });
  const response = await handler(
    binaryRequest('/nextState', toBinary(StepRequestSchema, request)),
  );

  assertEquals(response.status, 200, 'step should succeed');
  const decoded = fromBinary(
    StepResponseSchema,
    new Uint8Array(await response.arrayBuffer()),
  );
  assertEquals(
    JSON.stringify([...decoded.state]),
    JSON.stringify([4]),
    'handler should receive state',
  );
  assertEquals(decoded.effects[0]?.effect.case, 'emitBeep', 'handler effects should round-trip');
  assertEquals(receivedEffectLimit, 7, 'handler should receive the effect limit');
  assertEquals(receivedStateSizeLimit, 42n, 'handler should receive the state-size limit');
  assertEquals(receivedBurstChatTokens, 3, 'handler should receive burst chat tokens');
  assertEquals(receivedSlowChatTokens, 87, 'handler should receive slow chat tokens');
});

Deno.test('Program API round-trips ID-targeted and color-targeted SetRedstonePower effects', async () => {
  const powerHandler = createProgramApiHandler({
    startStates: {},
    program: (state) =>
      create(StepResponseSchema, {
        state,
        effects: [
          create(EffectSchema, {
            effect: {
              case: 'setRedstonePower',
              value: create(SetRedstonePowerSchema, {
                observerId: observerId(),
                powerLevel: 15,
              }),
            },
          }),
          create(EffectSchema, {
            effect: {
              case: 'setRedstonePower',
              value: create(SetRedstonePowerSchema, {
                observerColor: DyeColor.LIGHT_BLUE,
                powerLevel: 9,
              }),
            },
          }),
        ],
      }),
  });
  const request = create(StepRequestSchema, {
    state: new Uint8Array([3]),
    event: create(EventSchema, {
      event: {
        case: 'resumed',
        value: create(ResumedSchema, { stopTick: 3n, resumeTick: 4n }),
      },
    }),
  });

  const response = await powerHandler(
    binaryRequest('/nextState', toBinary(StepRequestSchema, request)),
  );
  const decoded = fromBinary(
    StepResponseSchema,
    new Uint8Array(await response.arrayBuffer()),
  );

  assertEquals(
    decoded.effects[0]?.effect.case,
    'setRedstonePower',
    'power effect should round-trip',
  );
  if (decoded.effects[0]?.effect.case !== 'setRedstonePower') {
    throw new Error('SetRedstonePower payload missing');
  }
  assertEquals(
    decoded.effects[0].effect.value.observerId?.high,
    10n,
    'observer id should round-trip',
  );
  assertEquals(decoded.effects[0].effect.value.powerLevel, 15, 'power level should round-trip');
  assertEquals(
    decoded.effects[1]?.effect.case,
    'setRedstonePower',
    'color power effect should round-trip',
  );
  if (decoded.effects[1]?.effect.case !== 'setRedstonePower') {
    throw new Error('color SetRedstonePower payload missing');
  }
  assertEquals(
    decoded.effects[1].effect.value.observerId,
    undefined,
    'color effect should not have an observer id',
  );
  assertEquals(
    decoded.effects[1].effect.value.observerColor,
    DyeColor.LIGHT_BLUE,
    'observer color should round-trip',
  );
  assertEquals(
    decoded.effects[1].effect.value.powerLevel,
    9,
    'color power level should round-trip',
  );
});

Deno.test('Program API delivers a BlockStateChange to the event handler', async () => {
  let received: BlockStateChange | undefined;
  const blockStateHandler = createProgramApiHandler({
    startStates: {},
    program: (state, event) => {
      if (event.event.case !== 'blockStateChange') throw new Error('unexpected event');
      received = event.event.value;
      return create(StepResponseSchema, { state, effects: [] });
    },
  });
  const request = create(StepRequestSchema, {
    state: new Uint8Array([3]),
    event: create(EventSchema, {
      event: {
        case: 'blockStateChange',
        value: create(BlockStateChangeSchema, {
          observerColor: DyeColor.RED,
          observerId: observerId(),
          observerDir: Direction.EAST,
          observerPos: globalPos(1, 64, 1),
          blockPos: globalPos(2, 64, 1),
          oldState: blockInfo('redstone_lamp', { lit: 'false' }),
          newState: blockInfo('redstone_lamp', { lit: 'true' }),
        }),
      },
    }),
  });

  const response = await blockStateHandler(
    binaryRequest('/nextState', toBinary(StepRequestSchema, request)),
  );

  assertEquals(response.status, 200, 'block-state step should succeed');
  if (received === undefined) throw new Error('handler did not receive the BlockStateChange');
  assertEquals(received.observerColor, DyeColor.RED, 'observer color should reach handler');
  assertEquals(received.observerId?.high, 10n, 'observer id should reach handler');
  assertEquals(received.newState?.properties.lit, 'true', 'new state should reach handler');
});

Deno.test('Program API delivers Wireless Observer pairing lifecycle events', async () => {
  const received: Event[] = [];
  const lifecycleHandler = createProgramApiHandler({
    startStates: {},
    program: (state, event) => {
      received.push(event);
      return create(StepResponseSchema, { state, effects: [] });
    },
  });
  const paired = create(EventSchema, {
    event: {
      case: 'wirelessObserverPaired',
      value: create(WirelessObserverPairedSchema, {
        observerColor: DyeColor.BLUE,
        observerId: observerId(),
        observerDir: Direction.NORTH,
        observerPos: globalPos(4, 65, -2),
      }),
    },
  });
  const unpaired = create(EventSchema, {
    event: {
      case: 'wirelessObserverUnpaired',
      value: create(WirelessObserverUnpairedSchema, {
        observerColor: DyeColor.BLUE,
        observerId: observerId(),
        observerDir: Direction.NORTH,
        observerPos: globalPos(4, 65, -2),
      }),
    },
  });

  for (const event of [paired, unpaired]) {
    const request = create(StepRequestSchema, { state: new Uint8Array(), event });
    const response = await lifecycleHandler(
      binaryRequest('/nextState', toBinary(StepRequestSchema, request)),
    );
    assertEquals(response.status, 200, 'lifecycle step should succeed');
  }

  assertEquals(
    received[0]?.event.case,
    'wirelessObserverPaired',
    'paired event should reach handler',
  );
  if (received[0]?.event.case !== 'wirelessObserverPaired') {
    throw new Error('paired lifecycle payload missing');
  }
  assertEquals(
    received[0].event.value.observerId?.low,
    20n,
    'paired event id should reach handler',
  );
  assertEquals(
    received[0].event.value.observerPos?.position?.z,
    -2,
    'paired position should reach handler',
  );
  assertEquals(
    received[1]?.event.case,
    'wirelessObserverUnpaired',
    'unpaired event should reach handler',
  );
  if (received[1]?.event.case !== 'wirelessObserverUnpaired') {
    throw new Error('unpaired lifecycle payload missing');
  }
  assertEquals(
    received[1].event.value.observerPos?.position?.z,
    -2,
    'unpaired position should reach handler',
  );
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
  const request = create(StepRequestSchema, {
    event: create(EventSchema, {
      event: {
        case: 'resumed',
        value: create(ResumedSchema, { stopTick: 12n, resumeTick: 15n }),
      },
    }),
  });
  const failed = await throwingHandler(
    binaryRequest('/nextState', toBinary(StepRequestSchema, request)),
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

let receivedEffectLimit: number | undefined;
let receivedStateSizeLimit: bigint | undefined;
let receivedBurstChatTokens: number | undefined;
let receivedSlowChatTokens: number | undefined;

const handle: Program = (state, event, context) => {
  if (event.event.case !== 'resumed') throw new Error('unexpected event');
  receivedEffectLimit = context.effectLimit;
  receivedStateSizeLimit = context.stateSizeLimit;
  receivedBurstChatTokens = context.burstChatTokens;
  receivedSlowChatTokens = context.slowChatTokens;
  return create(StepResponseSchema, {
    state: new Uint8Array([state[0]! + 1]),
    effects: [
      create(EffectSchema, {
        effect: { case: 'emitBeep', value: create(EmitBeepSchema, {}) },
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

function globalPos(x: number, y: number, z: number): GlobalPos {
  return create(GlobalPosSchema, {
    dimension: create(IdentifierSchema, { ns: 'minecraft', path: 'overworld' }),
    position: create(Vec3iSchema, { x, y, z }),
  });
}

function observerId(): Uuid {
  return create(UuidSchema, { high: 10n, low: 20n });
}

function blockInfo(path: string, properties: Record<string, string>): BlockInfo {
  return create(BlockInfoSchema, {
    blockType: create(IdentifierSchema, { ns: 'minecraft', path }),
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
    ErrorResponseSchema,
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
