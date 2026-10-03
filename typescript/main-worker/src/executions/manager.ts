import { Common, create, Effects, Events, fromBinary, Program, toBinary } from '@cobbleware/core';
import { isFieldSet } from '@bufbuild/protobuf';
import * as Internal from '@cobbleware/core/internal';
import type { ProgramStore } from '../programs/types.ts';
import { invokeNextState, StepResponseSizeLimitError } from '../programs/program-api.ts';

const RETIRE_SHUTDOWN_MS = 0;

type EventWindowSlot = {
  event: Events.Event;
  eventSeqNum: bigint;
  stateSha?: Uint8Array;
  effects: Effects.Effect[];
};

type InFlightStep = {
  token: object;
  eventSeqNum: bigint;
};

type ExecutionWindow = {
  playerId: string;
  executionId: Common.Uuid;
  initialEventSeqNum: bigint;
  initialStateSha: Uint8Array;
  currentState: Uint8Array;
  nextEventIndex: number;
  eventWindow: EventWindowSlot[];
  inFlightStep?: InFlightStep;
  stateSizeLimit: bigint;
  responseSizeLimit: bigint;
  outboxLimit: number;
  failed: boolean;
  failedEventSeqNum?: bigint;
  failureReason: Internal.ExecutionFailureReason;
};

type WorkerState = {
  lifecycleState: 'INITIALIZING' | 'RUNNING' | 'RETIRED';
  programId?: string;
  worker?: CobblewareUserWorker;
  inFlightStep?: object;
};

type ValidatedExecution = {
  playerId: string;
  execution: Internal.ExecutionState;
  initialStateSha: Uint8Array;
  responseSizeLimit: bigint;
};

type ValidatedPlayer = {
  playerId: string;
  outstandingEffectLimit: number;
  totalExecutionStateLimit: bigint;
  executions: ValidatedExecution[];
};

type ValidatedRequest = {
  workerLimit: number;
  players: ValidatedPlayer[];
};

export class ExecutionRequestError extends Error {
  override name = 'ExecutionRequestError';
}

export class ExecutionManager {
  private readonly workers = new Map<string, WorkerState>();
  private readonly executionWindows = new Map<string, ExecutionWindow>();
  private readonly playerExecutionIds = new Map<string, Set<string>>();
  private readonly playerEffectLimits = new Map<string, number>();
  private workerLimit = 0;
  private serial = Promise.resolve();

  constructor(
    private readonly programStore: ProgramStore,
    private readonly playerMemoryQuotaBytes: number,
  ) {}

  sync(request: Internal.ExecutionsRequest): Promise<Internal.ExecutionsResponse> {
    return this.withLock(() => this.syncLocked(request));
  }

  /** Retires a player's old worker after a newly uploaded program becomes active. */
  activeProgramChanged(playerId: string, programId: string): Promise<void> {
    return this.withLock(() => {
      const worker = this.workers.get(playerId);
      if (worker !== undefined && worker.programId !== programId) {
        this.retireWorker(playerId, worker);
      }
    });
  }

  private async syncLocked(
    request: Internal.ExecutionsRequest,
  ): Promise<Internal.ExecutionsResponse> {
    const validated = await validateRequest(request, this.executionWindows);
    this.workerLimit = validated.workerLimit;
    const requestedPlayers = new Set(validated.players.map((player) => player.playerId));
    const requestedExecutions = new Set(
      validated.players.flatMap((player) =>
        player.executions.map((execution) => uuidKey(execution.execution.executionId!))
      ),
    );

    for (const [executionId, window] of this.executionWindows) {
      if (!requestedExecutions.has(executionId)) {
        this.dropWindow(executionId, window);
      }
    }
    for (const [playerId, worker] of this.workers) {
      if (!requestedPlayers.has(playerId)) {
        this.retireWorker(playerId, worker);
      }
    }
    for (const playerId of this.playerEffectLimits.keys()) {
      if (!requestedPlayers.has(playerId)) {
        this.playerEffectLimits.delete(playerId);
      }
    }

    for (const player of validated.players) {
      this.playerEffectLimits.set(player.playerId, player.outstandingEffectLimit);
      for (const execution of player.executions) {
        const executionId = uuidKey(execution.execution.executionId!);
        const existing = this.executionWindows.get(executionId);
        if (existing === undefined) {
          this.addWindow(execution);
        } else if (!this.reconcileWindow(existing, execution)) {
          this.dropWindow(executionId, existing);
          this.addWindow(execution);
        }
      }
    }

    for (const player of validated.players) {
      this.schedulePlayer(player.playerId);
    }

    return create(Internal.ExecutionsResponseSchema, {
      results: validated.players.flatMap((player) =>
        player.executions.map((execution) =>
          executionResult(this.executionWindows.get(uuidKey(execution.execution.executionId!))!)
        )
      ),
    });
  }

  private addWindow(execution: ValidatedExecution): void {
    const state = execution.execution;
    const executionId = uuidKey(state.executionId!);
    const window: ExecutionWindow = {
      playerId: execution.playerId,
      executionId: cloneUuid(state.executionId!),
      initialEventSeqNum: state.eventSeqNum,
      initialStateSha: execution.initialStateSha,
      currentState: state.initialState.slice(),
      nextEventIndex: 0,
      eventWindow: state.events.map((event, index) => ({
        event: cloneEvent(event),
        eventSeqNum: state.eventSeqNum + BigInt(index),
        effects: [],
      })),
      stateSizeLimit: state.stateSizeLimit,
      responseSizeLimit: execution.responseSizeLimit,
      outboxLimit: state.outboxLimit,
      failed: false,
      failureReason: Internal.ExecutionFailureReason.UNSPECIFIED,
    };
    this.executionWindows.set(executionId, window);
    let executionIds = this.playerExecutionIds.get(execution.playerId);
    if (executionIds === undefined) {
      executionIds = new Set();
      this.playerExecutionIds.set(execution.playerId, executionIds);
    }
    executionIds.add(executionId);
  }

  private reconcileWindow(
    window: ExecutionWindow,
    execution: ValidatedExecution,
  ): boolean {
    const state = execution.execution;
    const boundary = findBoundary(window, state.eventSeqNum);
    if (boundary === undefined || !sameBytes(boundary.sha, execution.initialStateSha)) {
      return false;
    }

    const retainedSlots = window.eventWindow.slice(boundary.eventCount);
    if (
      state.events.length < retainedSlots.length ||
      !retainedSlots.every((slot, index) => sameEvent(slot.event, state.events[index]!))
    ) {
      return false;
    }

    window.eventWindow = retainedSlots;
    window.nextEventIndex -= boundary.eventCount;
    window.initialEventSeqNum = state.eventSeqNum;
    window.initialStateSha = execution.initialStateSha;
    window.stateSizeLimit = state.stateSizeLimit;
    window.responseSizeLimit = execution.responseSizeLimit;
    window.outboxLimit = state.outboxLimit;
    for (let index = retainedSlots.length; index < state.events.length; index += 1) {
      window.eventWindow.push({
        event: cloneEvent(state.events[index]!),
        eventSeqNum: state.eventSeqNum + BigInt(index),
        effects: [],
      });
    }
    if (
      window.failed && window.failedEventSeqNum !== undefined &&
      state.eventSeqNum > window.failedEventSeqNum
    ) {
      window.failed = false;
      window.failedEventSeqNum = undefined;
      window.failureReason = Internal.ExecutionFailureReason.UNSPECIFIED;
    }
    return true;
  }

  private dropWindow(executionId: string, window: ExecutionWindow): void {
    this.invalidateWindow(window);
    this.executionWindows.delete(executionId);
    const executionIds = this.playerExecutionIds.get(window.playerId);
    executionIds?.delete(executionId);
    if (executionIds?.size === 0) {
      this.playerExecutionIds.delete(window.playerId);
    }
  }

  private retireWorker(playerId: string, worker: WorkerState): void {
    if (worker.lifecycleState === 'RETIRED') {
      return;
    }
    worker.lifecycleState = 'RETIRED';
    for (const executionId of this.playerExecutionIds.get(playerId) ?? []) {
      const window = this.executionWindows.get(executionId);
      if (window !== undefined) {
        this.invalidateWindow(window);
      }
    }
    worker.worker?.retire(RETIRE_SHUTDOWN_MS);
  }

  private closeWorker(playerId: string, worker: WorkerState): void {
    if (this.workers.get(playerId) !== worker) {
      return;
    }
    for (const executionId of this.playerExecutionIds.get(playerId) ?? []) {
      const window = this.executionWindows.get(executionId);
      if (window !== undefined) {
        this.invalidateWindow(window);
      }
    }
    worker.lifecycleState = 'RETIRED';
    this.workers.delete(playerId);
  }

  private invalidateWindow(window: ExecutionWindow): void {
    const token = window.inFlightStep?.token;
    window.inFlightStep = undefined;
    if (token !== undefined) {
      const worker = this.workers.get(window.playerId);
      if (worker?.inFlightStep === token) {
        worker.inFlightStep = undefined;
      }
    }
  }

  private hasRunnableWindow(playerId: string): boolean {
    const effectLimit = this.playerEffectLimits.get(playerId);
    if (effectLimit === undefined || this.playerOutstandingEffects(playerId) > effectLimit) {
      return false;
    }
    for (const executionId of this.playerExecutionIds.get(playerId) ?? []) {
      const window = this.executionWindows.get(executionId);
      if (
        window !== undefined && !window.failed && window.inFlightStep === undefined &&
        window.nextEventIndex < window.eventWindow.length &&
        windowEffects(window).length <= window.outboxLimit
      ) {
        return true;
      }
    }
    return false;
  }

  private schedulePlayer(playerId: string): void {
    let worker = this.workers.get(playerId);
    if (
      worker?.lifecycleState === 'RETIRED' || worker?.lifecycleState === 'INITIALIZING' ||
      worker?.inFlightStep !== undefined
    ) {
      return;
    }
    const effectLimit = this.playerEffectLimits.get(playerId);
    if (effectLimit === undefined || this.playerOutstandingEffects(playerId) > effectLimit) {
      return;
    }
    for (const executionId of this.playerExecutionIds.get(playerId) ?? []) {
      const window = this.executionWindows.get(executionId);
      if (
        window === undefined || window.failed || window.inFlightStep !== undefined ||
        window.nextEventIndex >= window.eventWindow.length ||
        windowEffects(window).length > window.outboxLimit
      ) {
        continue;
      }
      if (worker === undefined) {
        if (this.workers.size >= this.workerLimit) {
          return;
        }
        worker = { lifecycleState: 'INITIALIZING' };
        this.workers.set(playerId, worker);
      }
      const slot = window.eventWindow[window.nextEventIndex]!;
      const token = {};
      window.inFlightStep = { token, eventSeqNum: slot.eventSeqNum };
      worker.inFlightStep = token;
      void this.runInvocation(window, worker, token, slot.eventSeqNum);
      return;
    }
  }

  private async runInvocation(
    window: ExecutionWindow,
    worker: WorkerState,
    token: object,
    eventSeqNum: bigint,
  ): Promise<void> {
    const userWorker = await this.startWorkerIfNeeded(window, worker, token, eventSeqNum);
    if (userWorker === undefined) {
      return;
    }
    try {
      const slot = window.eventWindow[window.nextEventIndex];
      if (slot === undefined || slot.eventSeqNum !== eventSeqNum) {
        return;
      }
      const step = await invokeNextState(
        userWorker,
        create(Program.StepRequestSchema, {
          executionId: cloneUuid(window.executionId),
          state: window.currentState.slice(),
          event: cloneEvent(slot.event),
          effectLimit: window.outboxLimit,
          stateSizeLimit: window.stateSizeLimit,
        }),
        window.responseSizeLimit,
      );
      const stateSha = await sha1(step.state);
      await this.withLock(() => {
        if (!this.isCurrentInvocation(window, worker, token, eventSeqNum)) {
          return;
        }
        if (BigInt(step.state.byteLength) > window.stateSizeLimit) {
          throw new StateSizeLimitError();
        }
        const currentSlot = window.eventWindow[window.nextEventIndex]!;
        window.currentState = step.state.slice();
        currentSlot.stateSha = stateSha;
        currentSlot.effects = step.effects.slice(0, window.outboxLimit).map(cloneEffect);
        window.nextEventIndex += 1;
        this.clearInvocation(window, worker, token);
        this.schedulePlayer(window.playerId);
      });
    } catch (error) {
      if (isWorkerAlreadyRetired(error)) {
        await this.withLock(() => {
          if (!this.isCurrentInvocation(window, worker, token, eventSeqNum)) {
            return;
          }
          // The Runtime rejected this request before it reached the program. Keep the event
          // pending while the retired worker's existing `closed` callback releases its slot.
          this.clearInvocation(window, worker, token);
          worker.lifecycleState = 'RETIRED';
        });
        return;
      }
      const stateSha = await sha1(window.currentState);
      await this.withLock(() => {
        if (!this.isCurrentInvocation(window, worker, token, eventSeqNum)) {
          return;
        }
        console.error(
          `Execution invocation failed (execution_id=${uuidKey(window.executionId)}, ` +
            `player_id=${window.playerId}, event_seq_num=${eventSeqNum}, ` +
            `event_type=${
              window.eventWindow[window.nextEventIndex]!.event.event.case ?? 'unset'
            }):`,
          error,
        );
        const slot = window.eventWindow[window.nextEventIndex]!;
        slot.stateSha = stateSha;
        slot.effects = [];
        window.nextEventIndex += 1;
        window.failed = true;
        window.failedEventSeqNum = eventSeqNum;
        window.failureReason = error instanceof StateSizeLimitError
          ? Internal.ExecutionFailureReason.STATE_SIZE_LIMIT
          : error instanceof StepResponseSizeLimitError
          ? Internal.ExecutionFailureReason.STEP_RESPONSE_SIZE_LIMIT
          : Internal.ExecutionFailureReason.UNSPECIFIED;
        this.clearInvocation(window, worker, token);
      });
    }
  }

  private async startWorkerIfNeeded(
    window: ExecutionWindow,
    worker: WorkerState,
    token: object,
    eventSeqNum: bigint,
  ): Promise<CobblewareUserWorker | undefined> {
    if (worker.lifecycleState === 'RUNNING') {
      return worker.worker;
    }
    if (worker.lifecycleState !== 'INITIALIZING') {
      return undefined;
    }

    let runningProgram;
    try {
      runningProgram = await this.programStore.run(window.playerId, {
        memoryQuotaBytes: this.playerMemoryQuotaBytes,
      });
    } catch (error) {
      console.error(`Unable to start worker for player_id=${window.playerId}:`, error);
      await this.withLock(() => {
        if (
          this.workers.get(window.playerId) === worker &&
          worker.lifecycleState === 'INITIALIZING'
        ) {
          this.clearInvocation(window, worker, token);
          this.workers.delete(window.playerId);
        }
      });
      return undefined;
    }

    let invocationIsCurrent = false;
    await this.withLock(() => {
      if (this.workers.get(window.playerId) !== worker) {
        runningProgram.worker.retire(RETIRE_SHUTDOWN_MS);
        return;
      }
      if (worker.lifecycleState !== 'INITIALIZING') {
        worker.worker = runningProgram.worker;
        void runningProgram.worker.closed.then(() =>
          this.withLock(() => this.closeWorker(window.playerId, worker))
        );
        runningProgram.worker.retire(RETIRE_SHUTDOWN_MS);
        return;
      }
      worker.programId = runningProgram.program.metadata.id;
      worker.worker = runningProgram.worker;
      worker.lifecycleState = 'RUNNING';
      void runningProgram.worker.closed.then(() =>
        this.withLock(() => this.closeWorker(window.playerId, worker))
      );
      invocationIsCurrent = this.isCurrentInvocation(window, worker, token, eventSeqNum);
      if (!invocationIsCurrent) {
        this.schedulePlayer(window.playerId);
      }
    });
    return invocationIsCurrent ? runningProgram.worker : undefined;
  }

  private isCurrentInvocation(
    window: ExecutionWindow,
    worker: WorkerState,
    token: object,
    eventSeqNum: bigint,
  ): boolean {
    return this.executionWindows.get(uuidKey(window.executionId)) === window &&
      this.workers.get(window.playerId) === worker && worker.lifecycleState === 'RUNNING' &&
      worker.inFlightStep === token && window.inFlightStep?.token === token &&
      window.inFlightStep.eventSeqNum === eventSeqNum;
  }

  private clearInvocation(window: ExecutionWindow, worker: WorkerState, token: object): void {
    if (window.inFlightStep?.token === token) {
      window.inFlightStep = undefined;
    }
    if (worker.inFlightStep === token) {
      worker.inFlightStep = undefined;
    }
  }

  private playerOutstandingEffects(playerId: string): number {
    let total = 0;
    for (const executionId of this.playerExecutionIds.get(playerId) ?? []) {
      const window = this.executionWindows.get(executionId);
      if (window !== undefined) {
        total += windowEffects(window).length;
      }
    }
    return total;
  }

  private async withLock<T>(operation: () => T | Promise<T>): Promise<T> {
    let release: () => void;
    const acquired = new Promise<void>((resolve) => {
      release = resolve;
    });
    const previous = this.serial;
    this.serial = previous.catch(() => {}).then(() => acquired);
    await previous.catch(() => {});
    try {
      return await operation();
    } finally {
      release!();
    }
  }
}

async function validateRequest(
  request: Internal.ExecutionsRequest,
  windows: ReadonlyMap<string, ExecutionWindow>,
): Promise<ValidatedRequest> {
  requireRequestField(request, Internal.ExecutionsRequestSchema.field.workerLimit, 'worker_limit');
  const playerIds = new Set<string>();
  const executionIds = new Set<string>();
  const players: Array<
    Omit<ValidatedPlayer, 'executions'> & { executions: Internal.ExecutionState[] }
  > = [];
  for (const player of request.players) {
    requireRequestField(player, Internal.PlayerExecutionStateSchema.field.playerId, 'player_id');
    requireRequestField(
      player,
      Internal.PlayerExecutionStateSchema.field.outstandingEffectLimit,
      'outstanding_effect_limit',
    );
    requireRequestField(
      player,
      Internal.PlayerExecutionStateSchema.field.totalExecutionStateLimit,
      'total_execution_state_limit',
    );
    if (player.totalExecutionStateLimit <= 0n) {
      invalidRequest('total_execution_state_limit must be positive');
    }
    const playerId = uuidString(player.playerId!);
    if (playerIds.has(playerId)) {
      invalidRequest('player_id is duplicated');
    }
    playerIds.add(playerId);
    const executions: Internal.ExecutionState[] = [];
    for (const execution of player.executions) {
      requireRequestField(
        execution,
        Internal.ExecutionStateSchema.field.executionId,
        'execution_id',
      );
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
        Internal.ExecutionStateSchema.field.stateSizeLimit,
        'state_size_limit',
      );
      requireRequestField(
        execution,
        Internal.ExecutionStateSchema.field.outboxLimit,
        'outbox_limit',
      );
      if (execution.eventSeqNum < 1n) {
        invalidRequest('event_seq_num must be at least one');
      }
      for (const event of execution.events) {
        if (event.event.case === undefined) {
          invalidRequest('event must select an event type');
        }
      }
      const executionId = uuidKey(execution.executionId!);
      if (executionIds.has(executionId)) {
        invalidRequest('execution_id is duplicated');
      }
      executionIds.add(executionId);
      const existing = windows.get(executionId);
      if (existing !== undefined && existing.playerId !== playerId) {
        invalidRequest('execution_id cannot change owner');
      }
      executions.push(execution);
    }
    players.push({
      playerId,
      outstandingEffectLimit: player.outstandingEffectLimit,
      totalExecutionStateLimit: player.totalExecutionStateLimit,
      executions,
    });
  }
  return {
    workerLimit: request.workerLimit,
    players: await Promise.all(players.map(async (player) => ({
      playerId: player.playerId,
      outstandingEffectLimit: player.outstandingEffectLimit,
      totalExecutionStateLimit: player.totalExecutionStateLimit,
      executions: await Promise.all(player.executions.map(async (execution) => ({
        playerId: player.playerId,
        execution,
        initialStateSha: await sha1(execution.initialState),
        responseSizeLimit: player.totalExecutionStateLimit * 2n,
      }))),
    }))),
  };
}

function findBoundary(
  window: ExecutionWindow,
  eventSeqNum: bigint,
): { eventCount: number; sha: Uint8Array } | undefined {
  if (eventSeqNum === window.initialEventSeqNum) {
    return { eventCount: 0, sha: window.initialStateSha };
  }
  const eventCount = Number(eventSeqNum - window.initialEventSeqNum);
  if (!Number.isSafeInteger(eventCount) || eventCount < 1 || eventCount > window.nextEventIndex) {
    return undefined;
  }
  const slot = window.eventWindow[eventCount - 1];
  return slot?.stateSha === undefined ? undefined : { eventCount, sha: slot.stateSha };
}

function executionResult(window: ExecutionWindow): Internal.ExecutionResult {
  return create(Internal.ExecutionResultSchema, {
    executionId: cloneUuid(window.executionId),
    finalState: window.currentState.slice(),
    effects: windowEffects(window).map(cloneEffect),
    eventSeqNum: window.nextEventIndex === 0
      ? window.initialEventSeqNum - 1n
      : window.eventWindow[window.nextEventIndex - 1]!.eventSeqNum,
    executionFailed: window.failed,
    failureReason: window.failureReason,
  });
}

function windowEffects(window: ExecutionWindow): Effects.Effect[] {
  return window.eventWindow.slice(0, window.nextEventIndex).flatMap((slot) => slot.effects);
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

function invalidRequest(message: string): never {
  throw new ExecutionRequestError(message);
}

class StateSizeLimitError extends Error {}

function isWorkerAlreadyRetired(error: unknown): boolean {
  return error instanceof Error && error.name === 'WorkerAlreadyRetired';
}

async function sha1(value: Uint8Array): Promise<Uint8Array> {
  const bytes = new Uint8Array(value);
  return new Uint8Array(await crypto.subtle.digest('SHA-1', bytes.buffer as ArrayBuffer));
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
  const value = uuid.high.toString(16).padStart(16, '0') + uuid.low.toString(16).padStart(16, '0');
  return `${value.slice(0, 8)}-${value.slice(8, 12)}-${value.slice(12, 16)}-${
    value.slice(16, 20)
  }-${value.slice(20)}`;
}
