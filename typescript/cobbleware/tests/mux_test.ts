import {
  create,
  muxedProgram,
  type Program,
  type StartOpts,
  type StepContext,
} from '../src/mod.ts';
import { EffectSchema, SendChatSchema } from '../generated/effects_pb.ts';
import { type Event, EventSchema, StartedSchema } from '../generated/events_pb.ts';
import { type StepResponse, StepResponseSchema } from '../generated/program_pb.ts';

const context: StepContext = {
  effectLimit: 12,
  stateSizeLimit: 100n,
  burstChatTokens: 7,
  slowChatTokens: 4,
};

Deno.test('muxedProgram creates tagged named start states and routes later steps', async () => {
  const calls: Array<[string, number[]]> = [];
  const sharedEffects = [
    create(EffectSchema, {
      effect: { case: 'sendChat', value: create(SendChatSchema, { msg: 'muxed' }) },
    }),
  ];
  const mux = await muxedProgram({
    foo: (state) => {
      calls.push(['foo', [...state]]);
      return response(new Uint8Array([1, 2]), sharedEffects);
    },
    bar: (state) => {
      calls.push(['bar', [...state]]);
      return response(new Uint8Array([3]), []);
    },
  });
  const program = requireProgram(mux);

  const fooStart = mux.startStates.foo!;
  assertEquals(fooStart.byteLength, 20, 'foo start state tag length');
  assertBytes(
    fooStart,
    new Uint8Array([
      0x0b,
      0xee,
      0xc7,
      0xb5,
      0xea,
      0x3f,
      0x0f,
      0xdb,
      0xc9,
      0x5d,
      0x0d,
      0xd4,
      0x7f,
      0x3c,
      0x5b,
      0xc2,
      0x75,
      0xda,
      0x8a,
      0x33,
    ]),
    'foo tag should be SHA-1(foo)',
  );
  assertEquals(mux.startStates.bar!.byteLength, 20, 'bar start state tag length');

  const first = program(fooStart, startedEvent(), context);
  assertBytes(first.state.subarray(0, 2), new Uint8Array([1, 2]), 'first state payload');
  assertBytes(first.state.subarray(2), fooStart, 'first state tag');
  if (first.effects[0]?.effect.case !== 'sendChat') {
    throw new Error('effects should pass through unchanged');
  }
  assertEquals(
    first.effects[0].effect.value.msg,
    'muxed',
    'effect payload should pass through unchanged',
  );

  const second = program(first.state, startedEvent(), context);
  assertBytes(second.state.subarray(0, 2), new Uint8Array([1, 2]), 'second state payload');
  assertBytes(second.state.subarray(2), fooStart, 'second state tag');

  program(mux.startStates.bar!, startedEvent(), context);
  assertEquals(
    JSON.stringify(calls),
    JSON.stringify([
      ['foo', []],
      ['foo', [1, 2]],
      ['bar', []],
    ]),
    'delegates should receive only their own state',
  );
});

Deno.test('muxedProgram delegates a zero-copy view and reserves tag capacity', async () => {
  let receivedState: Uint8Array | undefined;
  let receivedContext: StepContext | undefined;
  const mux = await muxedProgram({
    foo: (state, _event, stepContext) => {
      receivedState = state;
      receivedContext = stepContext;
      return response(new Uint8Array());
    },
  });
  const program = requireProgram(mux);
  const backing = new Uint8Array(30);
  const taggedState = new Uint8Array(backing.buffer, 3, 22);
  taggedState.set([9, 8]);
  taggedState.set(mux.startStates.foo!, 2);

  program(taggedState, startedEvent(), {
    effectLimit: 3,
    stateSizeLimit: 25n,
    burstChatTokens: 5,
    slowChatTokens: 8,
  });

  if (receivedState === undefined || receivedContext === undefined) {
    throw new Error('delegate was not called');
  }
  assertEquals(receivedState.buffer, taggedState.buffer, 'delegate state backing buffer');
  assertEquals(receivedState.byteOffset, taggedState.byteOffset, 'delegate state byte offset');
  assertBytes(receivedState, new Uint8Array([9, 8]), 'delegate state payload');
  assertEquals(receivedContext.effectLimit, 3, 'effect limit should be preserved');
  assertEquals(receivedContext.stateSizeLimit, 5n, 'tag capacity should be reserved');
  assertEquals(receivedContext.burstChatTokens, 5, 'burst chat tokens should be preserved');
  assertEquals(receivedContext.slowChatTokens, 8, 'slow chat tokens should be preserved');
});

Deno.test('muxedProgram rejects invalid tags and insufficient tag capacity', async () => {
  const mux = await muxedProgram({ foo: () => response(new Uint8Array()) });
  const program = requireProgram(mux);

  assertThrows(
    () => program(new Uint8Array(19), startedEvent(), context),
    'missing its 20-byte program tag',
  );
  assertThrows(
    () => program(new Uint8Array(20), startedEvent(), context),
    'unknown program tag',
  );
  assertThrows(
    () => program(mux.startStates.foo!, startedEvent(), { ...context, stateSizeLimit: 19n }),
    'at least 20 bytes of state capacity',
  );
});

function startedEvent(): Event {
  return create(EventSchema, {
    event: { case: 'started', value: create(StartedSchema, { tick: 1n }) },
  });
}

function response(state: Uint8Array, effects: StepResponse['effects'] = []): StepResponse {
  return create(StepResponseSchema, { state, effects });
}

function requireProgram(opts: StartOpts): Program {
  if (opts.program === undefined) throw new Error('mux did not return a program');
  return opts.program;
}

function assertBytes(actual: Uint8Array, expected: Uint8Array, message: string): void {
  assertEquals(JSON.stringify([...actual]), JSON.stringify([...expected]), message);
}

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) {
    throw new Error(`${message}: expected ${String(expected)}, got ${String(actual)}`);
  }
}

function assertThrows(action: () => void, expectedMessage: string): void {
  try {
    action();
  } catch (error) {
    if (error instanceof Error && error.message.includes(expectedMessage)) return;
    throw error;
  }
  throw new Error(`expected error containing ${expectedMessage}`);
}
