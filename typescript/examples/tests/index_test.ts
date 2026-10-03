import { create, Events, Program, toBinary } from '@cobbleware/core';
import { createExampleProgramApiHandler, handle, startStates } from '../src/index.ts';

Deno.test('hello start state greets on start and beeps on resume', () => {
  assertStep(
    startStates.hello,
    event('started', create(Events.StartedSchema, { tick: 1n })),
    [1],
    'sendChat',
    'Hello world',
  );
  assertStep(
    startStates.hello,
    event('resumed', create(Events.ResumedSchema, { stopTick: 1n, resumeTick: 2n })),
    [1],
    'emitBeep',
  );
});

Deno.test('echo start state counts and echoes chat messages', () => {
  let state = assertStep(
    startStates.echo.slice(),
    event('chatMessage', create(Events.ChatMessageSchema, { message: 'Testing' })),
    [2, 1],
    'sendChat',
    'Echo (1): Testing',
  );
  state = assertStep(
    state,
    event('chatMessage', create(Events.ChatMessageSchema, { message: 'Again' })),
    [2, 2],
    'sendChat',
    'Echo (2): Again',
  );
  assertEquals([...state], [2, 2], 'echo counter must persist in state');
});

Deno.test('bigresponse start state streams without completing for chat events', async () => {
  const handler = createExampleProgramApiHandler();
  const request = create(Program.StepRequestSchema, {
    state: startStates.bigresponse,
    event: event('chatMessage', create(Events.ChatMessageSchema, { message: 'Testing' })),
    effectLimit: 1,
    stateSizeLimit: 1n,
  });
  const response = await handler(
    new Request('http://cobbleware.local/nextState', {
      method: 'POST',
      body: toBinary(Program.StepRequestSchema, request),
    }),
  );
  const reader = response.body?.getReader();
  if (reader === undefined) throw new Error('bigresponse did not return a response stream');

  const first = await reader.read();
  if (first.done || first.value.byteLength === 0) {
    throw new Error('bigresponse stream did not produce response bytes');
  }
  await reader.cancel();
});

function event(
  eventCase: 'started' | 'resumed' | 'chatMessage',
  value: Events.Started | Events.Resumed | Events.ChatMessage,
): Events.Event {
  return create(Events.EventSchema, { event: { case: eventCase, value } as Events.Event['event'] });
}

function assertStep(
  state: Uint8Array,
  input: Events.Event,
  expectedState: number[],
  expectedEffect: 'emitBeep' | 'sendChat',
  expectedMessage?: string,
): Uint8Array {
  const output = handle(state, input, { effectLimit: 50, stateSizeLimit: 1024n });

  assertEquals([...output.state], expectedState, 'handler returned an unexpected state');
  assertEquals(output.effects.length, 1, 'handler must emit one effect');
  assertEquals(
    output.effects[0]?.effect.case,
    expectedEffect,
    'handler emitted an unexpected effect',
  );

  if (expectedMessage !== undefined) {
    const effect = output.effects[0]?.effect;
    if (effect?.case !== 'sendChat') {
      throw new Error('handler did not send chat');
    }
    assertEquals(effect.value.msg, expectedMessage, 'handler sent an unexpected chat message');
  }

  return output.state;
}

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(
      `${message}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`,
    );
  }
}
