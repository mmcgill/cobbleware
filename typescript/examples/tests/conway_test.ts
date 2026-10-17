import { create } from '@cobbleware/core';
import {
  DyeColor,
  type GlobalPos,
  GlobalPosSchema,
  IdentifierSchema,
  type Uuid,
  UuidSchema,
  Vec3iSchema,
} from '@cobbleware/core/common';
import type { Effect } from '@cobbleware/core/effects';
import {
  BlockInfoSchema,
  BlockStateChangeSchema,
  ChatMessageSchema,
  type Event,
  EventSchema,
  WirelessObserverPairedSchema,
  WirelessObserverUnpairedSchema,
} from '@cobbleware/core/events';
import {
  type ConwayBoard,
  conwayHandler,
  type ConwayObserver,
  type ConwaySimulationState,
  conwayStartState,
  createSimulationState,
  decodeConwayState,
  encodeConwayState,
  nextGeneration,
  randomizedAlive,
} from '../src/conway.ts';

Deno.test('Conway collects black observers during init and removes unpaired observers', () => {
  let state = conwayStartState;
  const pairedResult = step(state, paired(DyeColor.BLACK, 1, 2, 64, 3));
  assertChatEffect(pairedResult.effects[0], 'Conway: 1 black observers; screen 1x1');
  state = pairedResult.state;
  let decoded = decodeConwayState(state);
  assertEquals(decoded.mode, 'init', 'state should remain in init mode');
  if (decoded.mode !== 'init') throw new Error('expected init state');
  assertEquals(decoded.observers.length, 1, 'black observer should be recorded');
  assertEquals(decoded.observers[0]?.position, [2, 64, 3], 'observer position should persist');

  state = step(state, unpaired(DyeColor.BLACK, 1)).state;
  decoded = decodeConwayState(state);
  if (decoded.mode !== 'init') throw new Error('expected init state');
  assertEquals(decoded.observers.length, 0, 'unpaired observer should be removed');
});

Deno.test('Conway enters simulation with an empty board and randomizes at 25 percent on demand', () => {
  const simulation = createSimulationState([
    observer(1, 0, 0, 0),
    observer(2, 1, 0, 0),
  ]);

  assertEquals(simulation.width, 2, 'selected board width');
  assertEquals(simulation.height, 1, 'selected board height');
  assertEquals(simulation.alive, [], 'simulation must not randomly seed cells on entry');
  assertEquals(simulation.pending, [{ type: 'clear' }], 'initial state must clear the display');

  const board: ConwayBoard = { ...simulation, alive: [] };
  assertEquals(
    randomizedAlive(board, sequenceRandom([0.24, 0.25])),
    [0],
    'only random values below 0.25 should become live',
  );
});

Deno.test('Conway configuration commands work in init and retain their board when sim starts', () => {
  let state = step(conwayStartState, paired(DyeColor.BLACK, 1, 0, 0, 0), 0).state;
  state = step(state, paired(DyeColor.BLACK, 2, 1, 0, 0), 0).state;

  const originalRandom = Math.random;
  Math.random = sequenceRandom([0.24, 0.25]);
  try {
    const randomized = step(state, chatMessage('randomize'), 0);
    const randomizedInit = decodeConwayState(randomized.state);
    if (randomizedInit.mode !== 'init' || randomizedInit.board === undefined) {
      throw new Error('expected randomized init board');
    }
    assertEquals(randomizedInit.board.alive, [0], 'randomize should work in init mode');
  } finally {
    Math.random = originalRandom;
  }

  const cleared = step(state, chatMessage('clear'), 0);
  let init = decodeConwayState(cleared.state);
  if (init.mode !== 'init' || init.board === undefined) {
    throw new Error('expected configured init board');
  }
  assertEquals(init.board.alive, [], 'clear should make every init cell dead');
  assertEquals(init.pending, [{ type: 'clear' }], 'clear should replace stale display work');

  const templated = step(
    step(cleared.state, blackBlockChange(1, 'stone'), 0).state,
    chatMessage('template'),
    0,
  );
  init = decodeConwayState(templated.state);
  if (init.mode !== 'init' || init.board === undefined) {
    throw new Error('expected configured init board');
  }
  assertEquals(init.board.alive, [0], 'template should make a non-air observed cell live');
  assertEquals(init.pending.length, 2, 'template should clear before activating its live cells');

  const simulated = step(templated.state, chatMessage('sim'), 0);
  const simulation = simulationFrom(simulated.state);
  assertEquals(simulation.alive, [0], 'sim should preserve an init-mode configured board');

  const clearedSimulation = step(simulated.state, chatMessage('clear'), 0);
  assertEquals(
    simulationFrom(clearedSimulation.state).alive,
    [],
    'clear should also work in sim mode',
  );

  Math.random = sequenceRandom([0.25, 0.24]);
  try {
    const randomizedSimulation = step(clearedSimulation.state, chatMessage('randomize'), 0);
    assertEquals(
      simulationFrom(randomizedSimulation.state).alive,
      [1],
      'randomize should work in sim mode',
    );
  } finally {
    Math.random = originalRandom;
  }
});

Deno.test('Conway template tracks black observer blocks in both modes', () => {
  let state = step(conwayStartState, paired(DyeColor.BLACK, 1, 0, 0, 0), 0).state;
  state = step(state, paired(DyeColor.BLACK, 2, 1, 0, 0), 0).state;
  state = step(state, blackBlockChange(1, 'stone'), 0).state;
  state = step(state, blackBlockChange(2, 'air'), 0).state;

  const initTemplate = step(state, chatMessage('template'), 0);
  let init = decodeConwayState(initTemplate.state);
  if (init.mode !== 'init' || init.board === undefined) {
    throw new Error('expected configured init board');
  }
  assertEquals(init.board.alive, [0], 'air and never-seen observers should be dead in template');

  const simulated = step(initTemplate.state, chatMessage('sim'), 50);
  const afterAir = step(simulated.state, blackBlockChange(1, 'air'), 50);
  const simTemplate = step(afterAir.state, chatMessage('template'), 0);
  assertEquals(
    simulationFrom(simTemplate.state).alive,
    [],
    'sim-mode block changes should refresh template input',
  );

  const afterUnpair = step(state, unpaired(DyeColor.BLACK, 1), 0);
  init = decodeConwayState(afterUnpair.state);
  if (init.mode !== 'init') throw new Error('expected init state');
  assertEquals(
    init.lastSeenBlocks[idKey(1)],
    undefined,
    'unpairing should discard cached block input',
  );
});

Deno.test('Conway normalizes pre-template persisted state', () => {
  const legacy = {
    version: 1,
    mode: 'init',
    observers: [observer(1, 0, 0, 0)],
    pending: [],
  };
  const payload = new TextEncoder().encode(JSON.stringify(legacy));
  const bytes = new Uint8Array(payload.length + 1);
  bytes[0] = 4;
  bytes.set(payload, 1);

  const decoded = decodeConwayState(bytes);
  if (decoded.mode !== 'init') throw new Error('expected normalized init state');
  assertEquals(decoded.observers, legacy.observers, 'legacy observers should be retained');
  assertEquals(decoded.lastSeenBlocks, {}, 'legacy state should receive empty template input');
});

Deno.test('Conway selects an xy display plane and caps each board axis at 64 cells', () => {
  let state = conwayStartState;
  for (let x = 0; x < 65; x += 1) {
    state = step(state, paired(DyeColor.BLACK, x + 1, x, 0, 0)).state;
  }

  const result = step(state, blockChange(DyeColor.GREEN, true), 1);
  assertChatEffect(result.effects[0], 'Conway: simulate mode');
  const decoded = decodeConwayState(result.state);
  if (decoded.mode !== 'simulate') throw new Error('green power should start simulation');
  assertEquals(decoded.width, 64, 'width must be capped');
  assertEquals(decoded.height, 1, 'xy should win the plane tie');
  assertEquals(decoded.cells.length, 64, 'out-of-bounds observers must not become pixels');
});

Deno.test('Conway drains queued effects before calculating one next generation', () => {
  const initial = simulationState(5, 5, [11, 12, 13], [
    { type: 'clear' },
    { type: 'cell', observerId: idKey(12), powered: true },
  ]);
  const first = step(encodeConwayState(initial), blockChange(DyeColor.BLUE, true), 1);
  assertClearEffect(first.effects[0]);
  let decoded = simulationFrom(first.state);
  assertEquals(decoded.pending.length, 1, 'one queued operation should remain');
  assertEquals(decoded.alive, [11, 12, 13], 'generation must wait for the queue to empty');

  const second = step(first.state, blockChange(DyeColor.BLUE, false), 1);
  assertPowerEffect(second.effects[0], 12, 15);
  decoded = simulationFrom(second.state);
  assertEquals(decoded.alive, [7, 12, 17], 'the blinker should advance after its queue drains');
  assertEquals(decoded.pending.length, 4, 'only changed displayed cells should be queued');

  const zeroLimit = step(second.state, blockChange(DyeColor.BLUE, true), 0);
  decoded = simulationFrom(zeroLimit.state);
  assertEquals(zeroLimit.effects.length, 0, 'a zero effect limit must not emit effects');
  assertEquals(decoded.pending.length, 4, 'a zero effect limit must retain the entire queue');
  assertEquals(decoded.alive, [7, 12, 17], 'a queued generation must not advance again');
});

Deno.test('Conway applies Life rules across toroidal board edges', () => {
  assertEquals(
    nextGeneration(5, 5, [10, 11, 14]),
    [5, 10, 15],
    'edge-spanning blinker should wrap',
  );
  assertEquals(nextGeneration(5, 5, [11, 12, 13]), [7, 12, 17], 'interior blinker should rotate');
});

Deno.test('Conway green-off resets simulation and unrelated events do not advance it', () => {
  const initial = simulationState(3, 3, [3, 4, 5], []);
  const ignored = step(encodeConwayState(initial), blockChange(DyeColor.RED, true), 50);
  assertEquals(
    simulationFrom(ignored.state).alive,
    [3, 4, 5],
    'wrong-color event should be ignored',
  );

  const reset = step(ignored.state, blockChange(DyeColor.GREEN, false), 50);
  const decoded = decodeConwayState(reset.state);
  assertEquals(decoded.mode, 'init', 'green unpowered event should reset to init');
  assertClearEffect(reset.effects[0]);
  assertChatEffect(reset.effects[1], 'Conway: init mode');
});

Deno.test('Conway chat commands enter simulation, step it, and return to init', () => {
  const state = step(conwayStartState, paired(DyeColor.BLACK, 1, 0, 0, 0)).state;
  const simulated = step(state, chatMessage('sim'), 1);
  assertChatEffect(simulated.effects[0], 'Conway: simulate mode');
  assertEquals(
    decodeConwayState(simulated.state).mode,
    'simulate',
    'sim command should enter simulation',
  );
  const reinitialized = step(simulated.state, chatMessage('init'), 1);
  const initState = decodeConwayState(reinitialized.state);
  if (initState.mode !== 'init') throw new Error('expected init state');
  assertEquals(initState.observers.length, 1, 'init transition should retain paired observers');

  const steppingState = simulationState(5, 5, [11, 12, 13], []);
  const stepped = step(encodeConwayState(steppingState), chatMessage('step'));
  assertEquals(
    simulationFrom(stepped.state).alive,
    [7, 12, 17],
    'step command should advance Life',
  );

  const initialized = step(stepped.state, chatMessage('init'));
  assertEquals(decodeConwayState(initialized.state).mode, 'init', 'init command should reset');
  assertClearEffect(initialized.effects[0]);
  assertChatEffect(initialized.effects[1], 'Conway: init mode');
});

function step(state: Uint8Array, event: Event, effectLimit = 50) {
  return conwayHandler(state, event, {
    effectLimit,
    stateSizeLimit: 1024n,
    burstChatTokens: 10,
    slowChatTokens: 100,
  });
}

function paired(color: DyeColor, id: number, x: number, y: number, z: number): Event {
  return create(EventSchema, {
    event: {
      case: 'wirelessObserverPaired',
      value: create(WirelessObserverPairedSchema, {
        observerColor: color,
        observerId: uuid(id),
        observerPos: globalPos(x, y, z),
      }),
    },
  });
}

function unpaired(color: DyeColor, id: number): Event {
  return create(EventSchema, {
    event: {
      case: 'wirelessObserverUnpaired',
      value: create(WirelessObserverUnpairedSchema, {
        observerColor: color,
        observerId: uuid(id),
      }),
    },
  });
}

function blockChange(color: DyeColor, powered: boolean): Event {
  return create(EventSchema, {
    event: {
      case: 'blockStateChange',
      value: create(BlockStateChangeSchema, {
        observerColor: color,
        newState: create(BlockInfoSchema, { properties: { powered: String(powered) } }),
      }),
    },
  });
}

function blackBlockChange(id: number, blockPath: string): Event {
  return create(EventSchema, {
    event: {
      case: 'blockStateChange',
      value: create(BlockStateChangeSchema, {
        observerColor: DyeColor.BLACK,
        observerId: uuid(id),
        newState: create(BlockInfoSchema, {
          blockType: create(IdentifierSchema, { ns: 'minecraft', path: blockPath }),
        }),
      }),
    },
  });
}

function chatMessage(message: string): Event {
  return create(EventSchema, {
    event: {
      case: 'chatMessage',
      value: create(ChatMessageSchema, { message }),
    },
  });
}

function observer(id: number, x: number, y: number, z: number): ConwayObserver {
  return {
    id: idKey(id),
    dimension: ['minecraft', 'overworld'],
    position: [x, y, z],
  };
}

function simulationState(
  width: number,
  height: number,
  alive: number[],
  pending: ConwaySimulationState['pending'],
): ConwaySimulationState {
  return {
    version: 1,
    mode: 'simulate',
    observers: [],
    lastSeenBlocks: {},
    width,
    height,
    cells: Array.from(
      { length: width * height },
      (_, cell): [number, string] => [cell, idKey(cell + 1)],
    ),
    alive,
    pending,
  };
}

function simulationFrom(state: Uint8Array): ConwaySimulationState {
  const decoded = decodeConwayState(state);
  if (decoded.mode !== 'simulate') throw new Error('expected simulation state');
  return decoded;
}

function sequenceRandom(values: number[]): () => number {
  let index = 0;
  return () => values[index++] ?? 1;
}

function uuid(id: number): Uuid {
  return create(UuidSchema, { high: 0n, low: BigInt(id) });
}

function idKey(id: number): string {
  return id.toString(16).padStart(32, '0');
}

function globalPos(x: number, y: number, z: number): GlobalPos {
  return create(GlobalPosSchema, {
    dimension: create(IdentifierSchema, { ns: 'minecraft', path: 'overworld' }),
    position: create(Vec3iSchema, { x, y, z }),
  });
}

function assertClearEffect(effect: Effect | undefined): void {
  if (effect?.effect.case !== 'setRedstonePower') throw new Error('expected a redstone effect');
  assertEquals(
    effect.effect.value.observerColor,
    DyeColor.BLACK,
    'clear should select black observers',
  );
  assertEquals(effect.effect.value.powerLevel, 0, 'clear should turn observers off');
}

function assertChatEffect(effect: Effect | undefined, message: string): void {
  if (effect?.effect.case !== 'sendChat') throw new Error('expected a chat effect');
  assertEquals(effect.effect.value.msg, message, 'chat message');
}

function assertPowerEffect(
  effect: Effect | undefined,
  id: number,
  powerLevel: number,
): void {
  if (effect?.effect.case !== 'setRedstonePower') throw new Error('expected a redstone effect');
  assertEquals(effect.effect.value.observerId?.low, BigInt(id), 'target observer ID');
  assertEquals(effect.effect.value.powerLevel, powerLevel, 'power level');
}

function assertEquals<T>(actual: T, expected: T, message: string): void {
  const actualJson = stringify(actual);
  const expectedJson = stringify(expected);
  if (actualJson !== expectedJson) {
    throw new Error(`${message}: expected ${expectedJson}, got ${actualJson}`);
  }
}

function stringify(value: unknown): string | undefined {
  return JSON.stringify(
    value,
    (_key, entry) => typeof entry === 'bigint' ? entry.toString() : entry,
  );
}
