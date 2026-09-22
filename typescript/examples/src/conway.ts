import {
  Common,
  create,
  Effects,
  Events,
  Program,
  type Program as EventProgram,
} from '@cobbleware/core';

const { DyeColor } = Common;

export const CONWAY = 4;
const STATE_VERSION = 1;
const MAX_BOARD_DIMENSION = 64;
const encoder = new TextEncoder();
const decoder = new TextDecoder();

type Plane = 'xy' | 'xz' | 'yz';

export type ConwayObserver = {
  id: string;
  dimension: [string, string];
  position: [number, number, number];
};

type PendingOperation =
  | { type: 'clear' }
  | { type: 'cell'; observerId: string; powered: boolean }
  | { type: 'chat'; message: string };

export type ConwayInitState = {
  version: typeof STATE_VERSION;
  mode: 'init';
  observers: ConwayObserver[];
  pending: PendingOperation[];
};

export type ConwaySimulationState = {
  version: typeof STATE_VERSION;
  mode: 'simulate';
  observers: ConwayObserver[];
  width: number;
  height: number;
  cells: Array<[number, string]>;
  alive: number[];
  pending: PendingOperation[];
};

export type ConwayState = ConwayInitState | ConwaySimulationState;

export const conwayStartState = encodeConwayState(emptyInitState());

export const conwayHandler: EventProgram = (state, event, context) => {
  const current = decodeConwayState(state);
  if (current.mode === 'init') {
    return handleInit(current, event, context.effectLimit);
  }
  return handleSimulation(current, event, context.effectLimit);
};

/** Exported to keep Conway's persisted-state behavior directly testable. */
export function decodeConwayState(bytes: Uint8Array): ConwayState {
  if (bytes[0] !== CONWAY) {
    return emptyInitState();
  }

  try {
    const value: unknown = JSON.parse(decoder.decode(bytes.slice(1)));
    return isConwayState(value) ? value : emptyInitState();
  } catch {
    return emptyInitState();
  }
}

/** Exported to keep Conway's persisted-state behavior directly testable. */
export function encodeConwayState(state: ConwayState): Uint8Array {
  const payload = encoder.encode(JSON.stringify(state));
  const result = new Uint8Array(payload.length + 1);
  result[0] = CONWAY;
  result.set(payload, 1);
  return result;
}

/** Exported to make the toroidal Life rules independently testable. */
export function nextGeneration(
  width: number,
  height: number,
  alive: readonly number[],
): number[] {
  if (width === 0 || height === 0) {
    return [];
  }

  const current = new Set(alive);
  const next: number[] = [];
  for (let row = 0; row < height; row += 1) {
    for (let column = 0; column < width; column += 1) {
      const cell = row * width + column;
      let neighbors = 0;
      for (let rowOffset = -1; rowOffset <= 1; rowOffset += 1) {
        for (let columnOffset = -1; columnOffset <= 1; columnOffset += 1) {
          if (rowOffset === 0 && columnOffset === 0) continue;
          const neighborColumn = (column + columnOffset + width) % width;
          const neighborRow = (row + rowOffset + height) % height;
          if (current.has(neighborRow * width + neighborColumn)) {
            neighbors += 1;
          }
        }
      }
      if (neighbors === 3 || (current.has(cell) && neighbors === 2)) {
        next.push(cell);
      }
    }
  }
  return next;
}

function handleInit(
  state: ConwayInitState,
  event: Events.Event,
  effectLimit: number,
): Program.StepResponse {
  if (
    event.event.case === 'wirelessObserverPaired' &&
    event.event.value.observerColor === DyeColor.BLACK
  ) {
    const observer = observerFromEvent(event.event.value);
    if (observer !== undefined) {
      const observers = state.observers.filter((candidate) => candidate.id !== observer.id);
      observers.push(observer);
      const [width, height] = screenSize(observers);
      return drainAndRespond({
        ...state,
        observers,
        pending: [...state.pending, {
          type: 'chat',
          message: `Conway: ${observers.length} black observers; screen ${width}x${height}`,
        }],
      }, effectLimit);
    }
  }

  if (
    event.event.case === 'wirelessObserverUnpaired' &&
    event.event.value.observerColor === DyeColor.BLACK
  ) {
    const id = uuidKey(event.event.value.observerId);
    if (id !== undefined) {
      return drainAndRespond({
        ...state,
        observers: state.observers.filter((observer) => observer.id !== id),
      }, effectLimit);
    }
  }

  if (
    (event.event.case === 'blockStateChange' &&
      event.event.value.observerColor === DyeColor.GREEN && isPowered(event.event.value, true)) ||
    (event.event.case === 'chatMessage' && event.event.value.message === 'sim')
  ) {
    console.log('Entering sim state');
    const simulation = createSimulationState(state.observers, Math.random);
    simulation.pending.unshift(...state.pending, {
      type: 'chat',
      message: 'Conway: simulate mode',
    });
    return drainAndRespond(simulation, effectLimit);
  }

  return drainAndRespond(state, effectLimit);
}

function handleSimulation(
  state: ConwaySimulationState,
  event: Events.Event,
  effectLimit: number,
): Program.StepResponse {
  if (
    (event.event.case === 'blockStateChange' &&
      event.event.value.observerColor === DyeColor.GREEN && isPowered(event.event.value, false)) ||
    (event.event.case === 'chatMessage' && event.event.value.message === 'init')
  ) {
    console.log('Entering init state');
    return drainAndRespond({
      ...emptyInitState(),
      observers: state.observers,
      pending: [{ type: 'clear' }, { type: 'chat', message: 'Conway: init mode' }],
    }, effectLimit);
  }

  const steps = (event.event.case === 'blockStateChange' &&
    event.event.value.observerColor === DyeColor.BLUE) ||
    (event.event.case === 'chatMessage' && event.event.value.message === 'step');
  if (!steps) {
    console.log('manual step');
    return drainAndRespond(state, effectLimit);
  }

  const drained = drainPending(state, effectLimit);
  if (drained.state.pending.length !== 0) {
    console.log(`drain pending: ${drained.state.pending.length}`);
    return response(drained.state, drained.effects);
  }

  console.log(`tick`);
  const alive = nextGeneration(state.width, state.height, state.alive);
  const nextState: ConwaySimulationState = {
    ...drained.state,
    alive,
    pending: changedCellOperations(state.cells, state.alive, alive),
  };
  return response(nextState, drained.effects);
}

function emptyInitState(): ConwayInitState {
  return { version: STATE_VERSION, mode: 'init', observers: [], pending: [] };
}

/** Exported to make initialization and its random seeding directly testable. */
export function createSimulationState(
  observers: readonly ConwayObserver[],
  random: () => number,
): ConwaySimulationState {
  const display = selectDisplay(observers);
  if (display === undefined) {
    return {
      version: STATE_VERSION,
      mode: 'simulate',
      observers: [...observers],
      width: 0,
      height: 0,
      cells: [],
      alive: [],
      pending: [{ type: 'clear' }],
    };
  }

  const { plane, observers: selected } = display;
  const coordinates = selected.map((observer) => ({
    observer,
    coordinates: planeCoordinates(observer, plane),
  }));
  const minColumn = Math.min(...coordinates.map(({ coordinates }) => coordinates[0]));
  const minRow = Math.min(...coordinates.map(({ coordinates }) => coordinates[1]));
  const maxColumn = Math.max(...coordinates.map(({ coordinates }) => coordinates[0]));
  const maxRow = Math.max(...coordinates.map(({ coordinates }) => coordinates[1]));
  const width = Math.min(MAX_BOARD_DIMENSION, maxColumn - minColumn + 1);
  const height = Math.min(MAX_BOARD_DIMENSION, maxRow - minRow + 1);
  const cells = coordinates
    .filter(({ coordinates }) =>
      coordinates[0] < minColumn + width && coordinates[1] < minRow + height
    )
    .map(({ observer, coordinates }): [number, string] => [
      (coordinates[1] - minRow) * width + coordinates[0] - minColumn,
      observer.id,
    ])
    .sort(([leftCell, leftId], [rightCell, rightId]) =>
      leftCell - rightCell || compareStrings(leftId, rightId)
    );
  const alive = Array.from({ length: width * height }, (_, cell) => cell).filter(() =>
    random() < 0.25
  );

  return {
    version: STATE_VERSION,
    mode: 'simulate',
    observers: [...observers],
    width,
    height,
    cells,
    alive,
    pending: [{ type: 'clear' }, ...changedCellOperations(cells, [], alive)],
  };
}

function selectDisplay(observers: readonly ConwayObserver[]):
  | { plane: Plane; observers: ConwayObserver[] }
  | undefined {
  const candidates = new Map<
    string,
    { plane: Plane; dimension: [string, string]; constant: number; observers: ConwayObserver[] }
  >();
  for (const observer of observers) {
    for (const plane of ['xy', 'xz', 'yz'] as const) {
      const constant = planeCoordinates(observer, plane)[2];
      const key = `${plane}\u0000${observer.dimension[0]}\u0000${
        observer.dimension[1]
      }\u0000${constant}`;
      const candidate = candidates.get(key);
      if (candidate === undefined) {
        candidates.set(key, {
          plane,
          dimension: observer.dimension,
          constant,
          observers: [observer],
        });
      } else {
        candidate.observers.push(observer);
      }
    }
  }

  const ordered = [...candidates.values()].sort((left, right) =>
    right.observers.length - left.observers.length ||
    planeOrder(left.plane) - planeOrder(right.plane) ||
    compareStrings(left.dimension[0], right.dimension[0]) ||
    compareStrings(left.dimension[1], right.dimension[1]) ||
    left.constant - right.constant
  );
  const winner = ordered[0];
  return winner === undefined ? undefined : { plane: winner.plane, observers: winner.observers };
}

function planeCoordinates(observer: ConwayObserver, plane: Plane): [number, number, number] {
  const [x, y, z] = observer.position;
  switch (plane) {
    case 'xy':
      return [x, y, z];
    case 'xz':
      return [x, z, y];
    case 'yz':
      return [y, z, x];
  }
}

function changedCellOperations(
  cells: readonly [number, string][],
  before: readonly number[],
  after: readonly number[],
): PendingOperation[] {
  const beforeSet = new Set(before);
  const afterSet = new Set(after);
  return cells.filter(([cell]) => beforeSet.has(cell) !== afterSet.has(cell)).map((
    [cell, observerId],
  ) => ({
    type: 'cell' as const,
    observerId,
    powered: afterSet.has(cell),
  }));
}

function drainAndRespond<T extends ConwayState>(
  state: T,
  effectLimit: number,
): Program.StepResponse {
  const drained = drainPending(state, effectLimit);
  return response(drained.state, drained.effects);
}

function drainPending<T extends { pending: PendingOperation[] }>(
  state: T,
  effectLimit: number,
): { state: T; effects: Effects.Effect[] } {
  const count = Math.min(state.pending.length, Math.max(0, Math.floor(effectLimit)));
  const pending = state.pending.slice(count);
  const effects = state.pending.slice(0, count).map(effectForOperation);
  return { state: { ...state, pending }, effects };
}

function effectForOperation(operation: PendingOperation): Effects.Effect {
  if (operation.type === 'clear') {
    return create(Effects.EffectSchema, {
      effect: {
        case: 'setRedstonePower',
        value: create(Effects.SetRedstonePowerSchema, {
          observerColor: DyeColor.BLACK,
          powerLevel: 0,
        }),
      },
    });
  }

  if (operation.type === 'chat') {
    return create(Effects.EffectSchema, {
      effect: {
        case: 'sendChat',
        value: create(Effects.SendChatSchema, { msg: operation.message }),
      },
    });
  }

  return create(Effects.EffectSchema, {
    effect: {
      case: 'setRedstonePower',
      value: create(Effects.SetRedstonePowerSchema, {
        observerId: uuidFromKey(operation.observerId),
        powerLevel: operation.powered ? 15 : 0,
      }),
    },
  });
}

function response(state: ConwayState, effects: Effects.Effect[]): Program.StepResponse {
  return create(Program.StepResponseSchema, { state: encodeConwayState(state), effects });
}

function observerFromEvent(event: Events.WirelessObserverPaired): ConwayObserver | undefined {
  const id = uuidKey(event.observerId);
  const position = event.observerPos?.position;
  const dimension = event.observerPos?.dimension;
  if (id === undefined || position === undefined || dimension === undefined) {
    return undefined;
  }
  return {
    id,
    dimension: [dimension.ns, dimension.path],
    position: [position.x, position.y, position.z],
  };
}

function isPowered(event: Events.BlockStateChange, powered: boolean): boolean {
  return event.newState?.properties.powered === String(powered);
}

function uuidKey(uuid: Common.Uuid | undefined): string | undefined {
  if (uuid === undefined) return undefined;
  return `${uuid.high.toString(16).padStart(16, '0')}${uuid.low.toString(16).padStart(16, '0')}`;
}

function uuidFromKey(key: string): Common.Uuid {
  return create(Common.UuidSchema, {
    high: BigInt(`0x${key.slice(0, 16)}`),
    low: BigInt(`0x${key.slice(16)}`),
  });
}

function isConwayState(value: unknown): value is ConwayState {
  if (!isRecord(value) || value.version !== STATE_VERSION || typeof value.mode !== 'string') {
    return false;
  }
  if (value.mode === 'init') {
    return Array.isArray(value.observers) && value.observers.every(isObserver) &&
      Array.isArray(value.pending) && value.pending.every((operation) => isPending(operation));
  }
  if (value.mode !== 'simulate' || !isDimension(value.width) || !isDimension(value.height)) {
    return false;
  }
  const cellCount = value.width * value.height;
  return Array.isArray(value.observers) && value.observers.every(isObserver) &&
    Array.isArray(value.cells) && value.cells.every((cell) => isCell(cell, cellCount)) &&
    Array.isArray(value.alive) && value.alive.every((cell) => isCellIndex(cell, cellCount)) &&
    Array.isArray(value.pending) && value.pending.every((operation) => isPending(operation));
}

function isObserver(value: unknown): value is ConwayObserver {
  return isRecord(value) && typeof value.id === 'string' && /^[0-9a-f]{32}$/.test(value.id) &&
    isStringPair(value.dimension) && isPosition(value.position);
}

function isCell(value: unknown, cellCount: number): value is [number, string] {
  return Array.isArray(value) && value.length === 2 && isCellIndex(value[0], cellCount) &&
    typeof value[1] === 'string' && /^[0-9a-f]{32}$/.test(value[1]);
}

function isPending(value: unknown): value is PendingOperation {
  return isRecord(value) && (value.type === 'clear' || (
    value.type === 'cell' && typeof value.observerId === 'string' &&
    /^[0-9a-f]{32}$/.test(value.observerId) &&
    typeof value.powered === 'boolean'
  ) || value.type === 'chat' && typeof value.message === 'string');
}

function isDimension(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0 &&
    value <= MAX_BOARD_DIMENSION;
}

function isCellIndex(value: unknown, cellCount: number): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0 && value < cellCount;
}

function isPosition(value: unknown): value is [number, number, number] {
  return Array.isArray(value) && value.length === 3 && value.every(Number.isInteger);
}

function isStringPair(value: unknown): value is [string, string] {
  return Array.isArray(value) && value.length === 2 &&
    value.every((entry) => typeof entry === 'string');
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function planeOrder(plane: Plane): number {
  return plane === 'xy' ? 0 : plane === 'xz' ? 1 : 2;
}

function compareStrings(left: string, right: string): number {
  return left < right ? -1 : left > right ? 1 : 0;
}

function screenSize(observers: readonly ConwayObserver[]): [number, number] {
  const display = selectDisplay(observers);
  if (display === undefined) return [0, 0];
  const coordinates = display.observers.map((observer) =>
    planeCoordinates(observer, display.plane)
  );
  const width = Math.min(
    MAX_BOARD_DIMENSION,
    Math.max(...coordinates.map((coordinate) => coordinate[0])) -
      Math.min(...coordinates.map((coordinate) => coordinate[0])) + 1,
  );
  const height = Math.min(
    MAX_BOARD_DIMENSION,
    Math.max(...coordinates.map((coordinate) => coordinate[1])) -
      Math.min(...coordinates.map((coordinate) => coordinate[1])) + 1,
  );
  return [width, height];
}
