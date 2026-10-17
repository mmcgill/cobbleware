import { create, type Program } from '@cobbleware/core';
import * as Common from '@cobbleware/core/common';
import * as Effects from '@cobbleware/core/effects';
import * as Events from '@cobbleware/core/events';
import * as program from '@cobbleware/core/program';

const { DyeColor } = Common;

export const CONWAY = 4;
const STATE_VERSION = 1;
const MAX_BOARD_DIMENSION = 64;
const LIVE_PROBABILITY = 0.25;
const encoder = new TextEncoder();
const decoder = new TextDecoder();

type Plane = 'xy' | 'xz' | 'yz';
type ConfigurationCommand = 'clear' | 'randomize' | 'template';

export type ConwayObserver = {
  id: string;
  dimension: [string, string];
  position: [number, number, number];
};

export type ConwayBoard = {
  width: number;
  height: number;
  cells: Array<[number, string]>;
  alive: number[];
};

type BlockType = { ns: string; path: string };

type PendingOperation =
  | { type: 'clear' }
  | { type: 'cell'; observerId: string; powered: boolean }
  | { type: 'chat'; message: string };

export type ConwayInitState = {
  version: typeof STATE_VERSION;
  mode: 'init';
  observers: ConwayObserver[];
  lastSeenBlocks: Record<string, BlockType>;
  board?: ConwayBoard;
  pending: PendingOperation[];
};

export type ConwaySimulationState = ConwayBoard & {
  version: typeof STATE_VERSION;
  mode: 'simulate';
  observers: ConwayObserver[];
  lastSeenBlocks: Record<string, BlockType>;
  pending: PendingOperation[];
};

export type ConwayState = ConwayInitState | ConwaySimulationState;

export const conwayStartState = encodeConwayState(emptyInitState());

export const conwayHandler: Program = (state, event, context) => {
  const current = rememberBlackBlockState(decodeConwayState(state), event);
  const command = configurationCommand(event);
  if (command !== undefined) return configureBoard(current, command, context.effectLimit);
  return current.mode === 'init'
    ? handleInit(current, event, context.effectLimit)
    : handleSimulation(current, event, context.effectLimit);
};

/** Exported to keep Conway's persisted-state behavior directly testable. */
export function decodeConwayState(bytes: Uint8Array): ConwayState {
  if (bytes[0] !== CONWAY) return emptyInitState();
  try {
    return normalizeConwayState(JSON.parse(decoder.decode(bytes.slice(1)))) ?? emptyInitState();
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
export function nextGeneration(width: number, height: number, alive: readonly number[]): number[] {
  if (width === 0 || height === 0) return [];
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
          if (current.has(neighborRow * width + neighborColumn)) neighbors += 1;
        }
      }
      if (neighbors === 3 || (current.has(cell) && neighbors === 2)) next.push(cell);
    }
  }
  return next;
}

/** Exported to make deterministic randomization directly testable. */
export function randomizedAlive(board: ConwayBoard, random: () => number): number[] {
  return Array.from({ length: board.width * board.height }, (_, cell) => cell).filter(() =>
    random() < LIVE_PROBABILITY
  );
}

/** Exported to make default simulation initialization directly testable. */
export function createSimulationState(
  observers: readonly ConwayObserver[],
  lastSeenBlocks: Record<string, BlockType> = {},
): ConwaySimulationState {
  return simulationState(observers, lastSeenBlocks, createBoard(observers), [{ type: 'clear' }]);
}

function handleInit(
  state: ConwayInitState,
  event: Events.Event,
  effectLimit: number,
): program.StepResponse {
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
        board: undefined,
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
      const lastSeenBlocks = { ...state.lastSeenBlocks };
      delete lastSeenBlocks[id];
      return drainAndRespond({
        ...state,
        observers: state.observers.filter((observer) => observer.id !== id),
        lastSeenBlocks,
        board: undefined,
      }, effectLimit);
    }
  }

  if (
    (event.event.case === 'blockStateChange' &&
      event.event.value.observerColor === DyeColor.GREEN && isPowered(event.event.value, true)) ||
    (event.event.case === 'chatMessage' && event.event.value.message === 'sim')
  ) {
    const simulation = state.board === undefined
      ? createSimulationState(state.observers, state.lastSeenBlocks)
      : simulationState(state.observers, state.lastSeenBlocks, state.board, []);
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
): program.StepResponse {
  if (
    (event.event.case === 'blockStateChange' &&
      event.event.value.observerColor === DyeColor.GREEN && isPowered(event.event.value, false)) ||
    (event.event.case === 'chatMessage' && event.event.value.message === 'init')
  ) {
    return drainAndRespond({
      ...emptyInitState(),
      observers: state.observers,
      lastSeenBlocks: state.lastSeenBlocks,
      pending: [{ type: 'clear' }, { type: 'chat', message: 'Conway: init mode' }],
    }, effectLimit);
  }

  const steps = (event.event.case === 'blockStateChange' &&
    event.event.value.observerColor === DyeColor.BLUE) ||
    (event.event.case === 'chatMessage' && event.event.value.message === 'step');
  if (!steps) return drainAndRespond(state, effectLimit);

  const drained = drainPending(state, effectLimit);
  if (drained.state.pending.length !== 0) return response(drained.state, drained.effects);

  const tickStartedAt = performance.now();
  const alive = nextGeneration(state.width, state.height, state.alive);
  const nextState: ConwaySimulationState = {
    ...drained.state,
    alive,
    pending: changedCellOperations(state.cells, state.alive, alive),
  };
  console.debug(`Conway: simulation tick took ${(performance.now() - tickStartedAt).toFixed(2)}ms`);
  return response(nextState, drained.effects);
}

function configureBoard(
  state: ConwayState,
  command: ConfigurationCommand,
  effectLimit: number,
): program.StepResponse {
  const board = state.mode === 'init' ? createBoard(state.observers) : boardFrom(state);
  const alive = command === 'clear'
    ? []
    : command === 'randomize'
    ? randomizedAlive(board, Math.random)
    : templateAlive(board, state.lastSeenBlocks);
  const configured = { ...board, alive };
  const pending = displayOperations(configured);
  return state.mode === 'init'
    ? drainAndRespond({ ...state, board: configured, pending }, effectLimit)
    : drainAndRespond({ ...state, ...configured, pending }, effectLimit);
}

function emptyInitState(): ConwayInitState {
  return { version: STATE_VERSION, mode: 'init', observers: [], lastSeenBlocks: {}, pending: [] };
}

function createBoard(observers: readonly ConwayObserver[]): ConwayBoard {
  const display = selectDisplay(observers);
  if (display === undefined) return { width: 0, height: 0, cells: [], alive: [] };
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
  return { width, height, cells, alive: [] };
}

function simulationState(
  observers: readonly ConwayObserver[],
  lastSeenBlocks: Record<string, BlockType>,
  board: ConwayBoard,
  pending: PendingOperation[],
): ConwaySimulationState {
  return {
    version: STATE_VERSION,
    mode: 'simulate',
    observers: [...observers],
    lastSeenBlocks: { ...lastSeenBlocks },
    ...board,
    pending,
  };
}

function boardFrom(state: ConwaySimulationState): ConwayBoard {
  return { width: state.width, height: state.height, cells: state.cells, alive: state.alive };
}

function templateAlive(
  board: ConwayBoard,
  lastSeenBlocks: Readonly<Record<string, BlockType>>,
): number[] {
  return board.cells.filter(([, observerId]) => !isAir(lastSeenBlocks[observerId])).map(([cell]) =>
    cell
  );
}

function isAir(block: BlockType | undefined): boolean {
  return block === undefined || (block.ns === 'minecraft' && block.path === 'air');
}

function displayOperations(board: ConwayBoard): PendingOperation[] {
  return [{ type: 'clear' }, ...changedCellOperations(board.cells, [], board.alive)];
}

function rememberBlackBlockState<T extends ConwayState>(state: T, event: Events.Event): T {
  if (
    event.event.case !== 'blockStateChange' || event.event.value.observerColor !== DyeColor.BLACK
  ) {
    return state;
  }
  const id = uuidKey(event.event.value.observerId);
  const blockType = blockTypeFromEvent(event.event.value);
  if (
    id === undefined || blockType === undefined ||
    !state.observers.some((observer) => observer.id === id)
  ) {
    return state;
  }
  return { ...state, lastSeenBlocks: { ...state.lastSeenBlocks, [id]: blockType } };
}

function configurationCommand(event: Events.Event): ConfigurationCommand | undefined {
  if (event.event.case !== 'chatMessage') return undefined;
  switch (event.event.value.message) {
    case 'clear':
    case 'randomize':
    case 'template':
      return event.event.value.message;
    default:
      return undefined;
  }
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
): program.StepResponse {
  const drained = drainPending(state, effectLimit);
  return response(drained.state, drained.effects);
}

function drainPending<T extends { pending: PendingOperation[] }>(
  state: T,
  effectLimit: number,
): { state: T; effects: Effects.Effect[] } {
  const count = Math.min(state.pending.length, Math.max(0, Math.floor(effectLimit)));
  return {
    state: { ...state, pending: state.pending.slice(count) },
    effects: state.pending.slice(0, count).map(effectForOperation),
  };
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

function response(state: ConwayState, effects: Effects.Effect[]): program.StepResponse {
  return create(program.StepResponseSchema, { state: encodeConwayState(state), effects });
}

function observerFromEvent(event: Events.WirelessObserverPaired): ConwayObserver | undefined {
  const id = uuidKey(event.observerId);
  const position = event.observerPos?.position;
  const dimension = event.observerPos?.dimension;
  if (id === undefined || position === undefined || dimension === undefined) return undefined;
  return {
    id,
    dimension: [dimension.ns, dimension.path],
    position: [position.x, position.y, position.z],
  };
}

function blockTypeFromEvent(event: Events.BlockStateChange): BlockType | undefined {
  const blockType = event.newState?.blockType;
  if (
    blockType === undefined || typeof blockType.ns !== 'string' ||
    typeof blockType.path !== 'string'
  ) {
    return undefined;
  }
  return { ns: blockType.ns, path: blockType.path };
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

function normalizeConwayState(value: unknown): ConwayState | undefined {
  if (!isRecord(value) || value.version !== STATE_VERSION || typeof value.mode !== 'string') {
    return undefined;
  }
  const lastSeenBlocks = value.lastSeenBlocks === undefined
    ? {}
    : normalizeLastSeenBlocks(value.lastSeenBlocks);
  if (
    lastSeenBlocks === undefined || !Array.isArray(value.observers) ||
    !value.observers.every(isObserver) ||
    !Array.isArray(value.pending) || !value.pending.every(isPending)
  ) return undefined;
  if (value.mode === 'init') {
    const board = value.board === undefined ? undefined : normalizeBoard(value.board);
    if (value.board !== undefined && board === undefined) return undefined;
    return {
      version: STATE_VERSION,
      mode: 'init',
      observers: value.observers,
      lastSeenBlocks,
      ...(board === undefined ? {} : { board }),
      pending: value.pending,
    };
  }
  const board = normalizeBoard(value);
  if (value.mode !== 'simulate' || board === undefined) return undefined;
  return {
    version: STATE_VERSION,
    mode: 'simulate',
    observers: value.observers,
    lastSeenBlocks,
    ...board,
    pending: value.pending,
  };
}

function normalizeBoard(value: unknown): ConwayBoard | undefined {
  if (!isRecord(value) || !isDimension(value.width) || !isDimension(value.height)) return undefined;
  const cellCount = value.width * value.height;
  if (
    !Array.isArray(value.cells) || !value.cells.every((cell) => isCell(cell, cellCount)) ||
    !Array.isArray(value.alive) || !value.alive.every((cell) => isCellIndex(cell, cellCount))
  ) return undefined;
  return { width: value.width, height: value.height, cells: value.cells, alive: value.alive };
}

function normalizeLastSeenBlocks(value: unknown): Record<string, BlockType> | undefined {
  if (!isRecord(value)) return undefined;
  const result: Record<string, BlockType> = {};
  for (const [id, block] of Object.entries(value)) {
    if (!isUuidKey(id) || !isBlockType(block)) return undefined;
    result[id] = { ns: block.ns, path: block.path };
  }
  return result;
}

function isObserver(value: unknown): value is ConwayObserver {
  return isRecord(value) && isUuidKey(value.id) && isStringPair(value.dimension) &&
    isPosition(value.position);
}

function isBlockType(value: unknown): value is BlockType {
  return isRecord(value) && typeof value.ns === 'string' && typeof value.path === 'string';
}

function isCell(value: unknown, cellCount: number): value is [number, string] {
  return Array.isArray(value) && value.length === 2 && isCellIndex(value[0], cellCount) &&
    isUuidKey(value[1]);
}

function isPending(value: unknown): value is PendingOperation {
  return isRecord(value) && (value.type === 'clear' || (
    value.type === 'cell' && isUuidKey(value.observerId) && typeof value.powered === 'boolean'
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

function isUuidKey(value: unknown): value is string {
  return typeof value === 'string' && /^[0-9a-f]{32}$/.test(value);
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
