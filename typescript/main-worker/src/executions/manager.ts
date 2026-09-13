import { Common, create, Effects, Events, fromBinary, Program, toBinary } from '@cobbleware/core';
import { isFieldSet } from '@bufbuild/protobuf';
import * as Internal from '@cobbleware/core/internal';
import type { ActiveProgram, ProgramStore } from '../programs/types.ts';
import { PROGRAM_BINARY_CONTENT_TYPE, type ProgramRunner } from '../programs/start-states.ts';

export const MAX_EVENTS_PER_EXECUTION = 50;
export const MAX_EFFECTS_PER_EXECUTION = 50;

type CachedEffect = {
  sequence: bigint;
  effect: Effects.Effect;
};

type CachedExecution = {
  id: Common.Uuid;
  ownerKey: string;
  state: Internal.ExecutionStatus;
  baseState: Uint8Array;
  baseEventSeqNum: bigint;
  currentState: Uint8Array;
  lastConsumedEventSeqNum: bigint;
  events: Events.Event[];
  outbox: CachedEffect[];
  nextEffectSeqNum: bigint;
  inFlight?: { eventSeqNum: bigint; generation: number };
  generation: number;
};

export type ExecutionCoordinator = {
  sync(sourceRequest: Request, request: Internal.ExecutionsRequest): Internal.ExecutionsResponse;
};

export class ExecutionRequestError extends Error {
  override name = 'ExecutionRequestError';
}

export class ExecutionManager implements ExecutionCoordinator {
  private readonly entries = new Map<string, CachedExecution>();

  constructor(
    private readonly programStore: ProgramStore,
    private readonly programRunner: ProgramRunner,
  ) {}

  sync(sourceRequest: Request, request: Internal.ExecutionsRequest): Internal.ExecutionsResponse {
    validateRequest(request, this.entries);

    const requestedIds = new Set(
      request.executions.map((execution) => uuidKey(execution.executionId!)),
    );
    for (const [key, entry] of this.entries) {
      if (!requestedIds.has(key)) {
        entry.generation += 1;
        this.entries.delete(key);
      }
    }

    const entries: CachedExecution[] = [];
    for (const execution of request.executions) {
      const key = uuidKey(execution.executionId!);
      const existing = this.entries.get(key);
      const entry = existing === undefined || !isCompatible(existing, execution)
        ? this.replaceEntry(key, existing, execution)
        : reconcileEntry(existing, execution);
      entries.push(entry);
    }

    for (const entry of entries) {
      this.schedule(sourceRequest, entry);
    }

    return create(Internal.ExecutionsResponseSchema, {
      results: entries.map((entry) => executionResult(entry)),
    });
  }

  private replaceEntry(
    key: string,
    existing: CachedExecution | undefined,
    execution: Internal.ExecutionState,
  ): CachedExecution {
    if (existing !== undefined) {
      existing.generation += 1;
    }

    const entry = createEntry(execution, existing?.generation ?? 0);
    this.entries.set(key, entry);
    return entry;
  }

  private schedule(sourceRequest: Request, entry: CachedExecution): void {
    if (entry.state !== Internal.ExecutionStatus.RUNNING || entry.inFlight !== undefined) {
      return;
    }

    const offset = Number(entry.lastConsumedEventSeqNum + 1n - entry.baseEventSeqNum);
    const event = entry.events[offset];
    if (event === undefined) {
      return;
    }

    const eventSeqNum = entry.lastConsumedEventSeqNum + 1n;
    const generation = entry.generation;
    entry.inFlight = { eventSeqNum, generation };
    void this.runInvocation(sourceRequest, entry, event, eventSeqNum, generation);
  }

  private async runInvocation(
    sourceRequest: Request,
    entry: CachedExecution,
    event: Events.Event,
    eventSeqNum: bigint,
    generation: number,
  ): Promise<void> {
    try {
      const program = await this.programStore.readActive(entry.ownerKey);
      if (program === null) {
        throw new Error('player has no active program');
      }

      const effectLimit = Math.max(0, MAX_EFFECTS_PER_EXECUTION - entry.outbox.length);
      const response = await this.runProgram(sourceRequest, entry, event, program, effectLimit);
      const step = await decodeStepResponse(response);
      if (!this.isCurrent(entry, generation, eventSeqNum)) {
        return;
      }

      entry.currentState = step.state.slice();
      entry.lastConsumedEventSeqNum = eventSeqNum;
      for (const effect of step.effects.slice(0, effectLimit)) {
        entry.outbox.push({
          sequence: entry.nextEffectSeqNum,
          effect: cloneEffect(effect),
        });
        entry.nextEffectSeqNum += 1n;
      }
      entry.inFlight = undefined;
      this.schedule(sourceRequest, entry);
    } catch (error) {
      if (!this.isCurrent(entry, generation, eventSeqNum)) {
        return;
      }

      console.error(
        `Execution invocation failed (execution_id=${uuidKey(entry.id)}, ` +
          `player_id=${entry.ownerKey}, event_seq_num=${eventSeqNum}, ` +
          `event_type=${event.event.case ?? 'unset'}):`,
        error,
      );
      entry.lastConsumedEventSeqNum = eventSeqNum;
      entry.state = Internal.ExecutionStatus.CRASHED;
      entry.events = entry.events.slice(0, Number(eventSeqNum - entry.baseEventSeqNum + 1n));
      entry.inFlight = undefined;
    }
  }

  private async runProgram(
    sourceRequest: Request,
    entry: CachedExecution,
    event: Events.Event,
    program: ActiveProgram,
    effectLimit: number,
  ): Promise<Response> {
    const request = create(Program.StepRequestSchema, {
      executionId: cloneUuid(entry.id),
      state: entry.currentState.slice(),
      event: cloneEvent(event),
      effectLimit,
    });
    return await this.programRunner.step(
      sourceRequest,
      entry.ownerKey,
      program,
      toBinary(Program.StepRequestSchema, request),
    );
  }

  private isCurrent(entry: CachedExecution, generation: number, eventSeqNum: bigint): boolean {
    return this.entries.get(uuidKey(entry.id)) === entry &&
      entry.generation === generation &&
      entry.inFlight?.eventSeqNum === eventSeqNum &&
      entry.inFlight.generation === generation;
  }
}

function validateRequest(
  request: Internal.ExecutionsRequest,
  entries: ReadonlyMap<string, CachedExecution>,
): void {
  const seen = new Set<string>();
  for (const execution of request.executions) {
    requireRequestField(execution, Internal.ExecutionStateSchema.field.executionId, 'execution_id');
    requireRequestField(
      execution,
      Internal.ExecutionStateSchema.field.initialState,
      'initial_state',
    );
    requireRequestField(
      execution,
      Internal.ExecutionStateSchema.field.eventSeqNum,
      'event_seq_num',
    );
    requireRequestField(
      execution,
      Internal.ExecutionStateSchema.field.effectSeqNum,
      'effect_seq_num',
    );
    requireRequestField(execution, Internal.ExecutionStateSchema.field.playerId, 'player_id');
    requireRequestField(execution, Internal.ExecutionStateSchema.field.state, 'state');
    if (execution.eventSeqNum < 1n) {
      invalidRequest('event_seq_num must be at least one');
    }
    if (execution.state === Internal.ExecutionStatus.UNSPECIFIED) {
      invalidRequest('execution state must not be unspecified');
    }
    for (const event of execution.events) {
      if (event.event.case === undefined) {
        invalidRequest('event must select an event type');
      }
    }

    const key = uuidKey(execution.executionId!);
    if (seen.has(key)) {
      invalidRequest('execution_id is duplicated');
    }
    seen.add(key);
    const existing = entries.get(key);
    if (existing !== undefined && existing.ownerKey !== uuidString(execution.playerId!)) {
      invalidRequest('execution_id cannot change owner');
    }
  }
}

function requireRequestField(
  message: object,
  field: Parameters<typeof isFieldSet>[1],
  name: string,
): void {
  if (!isFieldSet(message as never, field)) {
    invalidRequest(`${name} is required`);
  }
}

function requireField(
  message: object,
  field: Parameters<typeof isFieldSet>[1],
  name: string,
): void {
  if (!isFieldSet(message as never, field)) {
    throw new Error(`${name} is required`);
  }
}

function invalidRequest(message: string): never {
  throw new ExecutionRequestError(message);
}

function createEntry(execution: Internal.ExecutionState, priorGeneration: number): CachedExecution {
  const state = execution.state;
  const events = execution.events.slice(0, MAX_EVENTS_PER_EXECUTION).map(cloneEvent);
  return {
    id: cloneUuid(execution.executionId!),
    ownerKey: uuidString(execution.playerId!),
    state,
    baseState: execution.initialState.slice(),
    baseEventSeqNum: execution.eventSeqNum,
    currentState: execution.initialState.slice(),
    lastConsumedEventSeqNum: execution.eventSeqNum - 1n,
    events: state === Internal.ExecutionStatus.CRASHED ? [] : events,
    outbox: [],
    nextEffectSeqNum: execution.effectSeqNum + 1n,
    generation: priorGeneration + 1,
  };
}

function isCompatible(entry: CachedExecution, execution: Internal.ExecutionState): boolean {
  if (entry.state === Internal.ExecutionStatus.CRASHED) {
    return true;
  }
  const state = stateAtBoundary(entry, execution.eventSeqNum);
  if (state === undefined || !sameBytes(state, execution.initialState)) {
    return false;
  }

  const eventOffset = Number(execution.eventSeqNum - entry.baseEventSeqNum);
  const existingEvents = entry.events.slice(Math.max(0, eventOffset));
  if (existingEvents.length > execution.events.length) {
    return false;
  }
  return existingEvents.every((event, index) => sameEvent(event, execution.events[index]!));
}

function reconcileEntry(
  entry: CachedExecution,
  execution: Internal.ExecutionState,
): CachedExecution {
  if (
    entry.state === Internal.ExecutionStatus.CRASHED ||
    execution.state === Internal.ExecutionStatus.CRASHED
  ) {
    entry.generation += 1;
    entry.state = Internal.ExecutionStatus.CRASHED;
    entry.events = [];
    entry.inFlight = undefined;
    acknowledgeEffects(entry, execution.effectSeqNum);
    return entry;
  }

  if (entry.state !== execution.state && entry.inFlight !== undefined) {
    entry.generation += 1;
    entry.inFlight = undefined;
  }

  if (
    execution.eventSeqNum === entry.lastConsumedEventSeqNum + 1n &&
    execution.eventSeqNum !== entry.baseEventSeqNum
  ) {
    const consumedCount = Number(execution.eventSeqNum - entry.baseEventSeqNum);
    entry.events = entry.events.slice(consumedCount);
    entry.baseEventSeqNum = execution.eventSeqNum;
    entry.baseState = execution.initialState.slice();
  }

  const knownCount = entry.events.length;
  const end = Math.min(execution.events.length, MAX_EVENTS_PER_EXECUTION);
  for (let index = knownCount; index < end; index += 1) {
    entry.events.push(cloneEvent(execution.events[index]!));
  }
  entry.state = execution.state;
  acknowledgeEffects(entry, execution.effectSeqNum);
  return entry;
}

function stateAtBoundary(entry: CachedExecution, eventSeqNum: bigint): Uint8Array | undefined {
  if (eventSeqNum === entry.baseEventSeqNum) {
    return entry.baseState;
  }
  if (eventSeqNum === entry.lastConsumedEventSeqNum + 1n) {
    return entry.currentState;
  }
  return undefined;
}

function acknowledgeEffects(entry: CachedExecution, acknowledgedThrough: bigint): void {
  entry.outbox = entry.outbox.filter((effect) => effect.sequence > acknowledgedThrough);
  const next = acknowledgedThrough + 1n;
  if (entry.nextEffectSeqNum < next) {
    entry.nextEffectSeqNum = next;
  }
}

function executionResult(entry: CachedExecution): Internal.ExecutionResult {
  return create(Internal.ExecutionResultSchema, {
    executionId: cloneUuid(entry.id),
    finalState: entry.currentState.slice(),
    effects: entry.outbox.map((effect) => cloneEffect(effect.effect)),
    eventSeqNum: entry.lastConsumedEventSeqNum,
    effectSeqNum: entry.outbox[0]?.sequence ?? entry.nextEffectSeqNum,
    state: entry.state,
  });
}

async function decodeStepResponse(response: Response): Promise<Program.StepResponse> {
  if (response.status !== 200) {
    const contentType = mediaType(response.headers.get('content-type'));
    let detail = '';
    if (contentType === PROGRAM_BINARY_CONTENT_TYPE) {
      try {
        const error = fromBinary(
          Program.ErrorResponseSchema,
          new Uint8Array(await response.arrayBuffer()),
        );
        if (error.error !== '') {
          detail = `: ${error.error}`;
        }
      } catch {
        detail = ' (response body was not a valid ErrorResponse)';
      }
    }
    throw new Error(
      `program returned HTTP ${response.status} with content type ${
        contentType ?? 'absent'
      }${detail}`,
    );
  }
  if (mediaType(response.headers.get('content-type')) !== PROGRAM_BINARY_CONTENT_TYPE) {
    throw new Error('program returned an unexpected /nextState content type');
  }
  const step = fromBinary(Program.StepResponseSchema, new Uint8Array(await response.arrayBuffer()));
  requireField(step, Program.StepResponseSchema.field.state, 'program response state');
  for (const effect of step.effects) {
    if (effect.effect.case === undefined) {
      throw new Error('program response effect is invalid');
    }
  }
  return step;
}

function mediaType(value: string | null): string | undefined {
  return value?.split(';', 1)[0].trim().toLowerCase();
}

function cloneUuid(uuid: Common.Uuid): Common.Uuid {
  return create(Common.UuidSchema, { high: uuid.high, low: uuid.low });
}

function cloneEvent(event: Events.Event): Events.Event {
  return fromBinary(Events.EventSchema, toBinary(Events.EventSchema, event));
}

function cloneEffect(effect: Effects.Effect): Effects.Effect {
  return fromBinary(Effects.EffectSchema, toBinary(Effects.EffectSchema, effect));
}

function sameEvent(left: Events.Event, right: Events.Event): boolean {
  return sameBytes(toBinary(Events.EventSchema, left), toBinary(Events.EventSchema, right));
}

function sameBytes(left: Uint8Array, right: Uint8Array): boolean {
  return left.byteLength === right.byteLength &&
    left.every((value, index) => value === right[index]);
}

function uuidKey(uuid: Common.Uuid): string {
  return `${uuid.high}:${uuid.low}`;
}

function uuidString(uuid: Common.Uuid): string {
  const value = uuid.high.toString(16).padStart(16, '0') +
    uuid.low.toString(16).padStart(16, '0');
  return `${value.slice(0, 8)}-${value.slice(8, 12)}-${value.slice(12, 16)}-${
    value.slice(16, 20)
  }-${value.slice(20)}`;
}
