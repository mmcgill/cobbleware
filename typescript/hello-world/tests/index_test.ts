import { Common, create, Events, fromBinary, Program, toBinary } from '@cobbleware/core';
import { handle, handleProgramApi } from '../src/index.ts';

Deno.test('handle preserves state and emits a beep for a reset', () => {
  const state = new Uint8Array([1, 2, 3]);
  const output = handle(
    state,
    create(Events.EventSchema, {
      event: {
        case: 'puterReset',
        value: create(Events.PuterResetSchema, { tick: 1n }),
      },
    }),
  );

  if (JSON.stringify([...output.state]) !== JSON.stringify([...state])) {
    throw new Error('handler changed the state');
  }

  if (output.effects.length !== 1) {
    throw new Error(`expected one effect, got ${output.effects.length}`);
  }

  const [effect] = output.effects;
  if (effect?.effect.case !== 'emitBeep') {
    throw new Error(`unexpected effect: ${JSON.stringify(effect)}`);
  }
});

Deno.test('Program API returns the hello-world start states', async () => {
  const response = await handleProgramApi(new Request('http://localhost/startStates'));
  if (response.status !== 200) {
    throw new Error(`expected status 200, got ${response.status}`);
  }
  if (response.headers.get('content-type') !== 'application/octet-stream') {
    throw new Error('expected an application/octet-stream response');
  }

  const decoded = fromBinary(
    Program.StartStatesResponseSchema,
    new Uint8Array(await response.arrayBuffer()),
  );
  const states = decoded.startStates.map((state) => [state.stateName, [...state.state]]);
  if (JSON.stringify(states) !== JSON.stringify([['one', [1]], ['two', [2]]])) {
    throw new Error(`unexpected start states: ${JSON.stringify(states)}`);
  }
});

Deno.test('Program API applies the event from a StepRequest', async () => {
  const request = create(Program.StepRequestSchema, {
    puterId: create(Common.UuidSchema, { high: 1n, low: 2n }),
    state: new Uint8Array([1, 2, 3]),
    event: create(Events.EventSchema, {
      event: {
        case: 'puterPlaced',
        value: create(Events.PuterPlacedSchema, {
          levelKey: create(Common.IdentifierSchema, { ns: 'minecraft', path: 'overworld' }),
          pos: create(Common.Vec3Schema, { x: 1, y: 2, z: 3 }),
        }),
      },
    }),
  });
  const body = toBinary(Program.StepRequestSchema, request);

  const response = await handleProgramApi(
    new Request('http://localhost/nextState', {
      method: 'POST',
      headers: { 'content-type': 'application/octet-stream' },
      body: new Blob([body.slice().buffer as ArrayBuffer]),
    }),
  );
  if (response.status !== 200) {
    throw new Error(`expected status 200, got ${response.status}`);
  }

  const decoded = fromBinary(
    Program.StepResponseSchema,
    new Uint8Array(await response.arrayBuffer()),
  );
  if (JSON.stringify([...decoded.state]) !== JSON.stringify([1, 2, 3])) {
    throw new Error(`unexpected next state: ${JSON.stringify([...decoded.state])}`);
  }
  if (decoded.effects.length !== 1 || decoded.effects[0]?.effect.case !== 'sendChat') {
    throw new Error('expected one SendChat effect');
  }
});
