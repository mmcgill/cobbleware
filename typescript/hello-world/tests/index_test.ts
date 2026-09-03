import * as flatbuffers from 'flatbuffers';
import { Common, Effects, Events, Program } from '@cobbleware/core';
import { handle, handleProgramApi } from '../src/index.ts';

Deno.test('handle preserves state and emits Hello world', () => {
  const state = new Uint8Array([1, 2, 3]);
  const output = handle(state, Events.Event.PuterPlaced);
  const outputState = output.stateArray();

  if (outputState === null || JSON.stringify([...outputState]) !== JSON.stringify([...state])) {
    throw new Error('handler changed the state');
  }

  const effects = output.unpack().effects;
  if (effects.length !== 1) {
    throw new Error(`expected one effect, got ${effects.length}`);
  }

  const [effect] = effects;
  if (!(effect instanceof Effects.SendChatT) || effect.msg !== 'Hello world') {
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

  const decoded = Program.StartStatesResponse.getRootAsStartStatesResponse(
    new flatbuffers.ByteBuffer(new Uint8Array(await response.arrayBuffer())),
  ).unpack();
  const states = decoded.startStates.map((state) => [state.stateName, state.state]);
  if (JSON.stringify(states) !== JSON.stringify([['one', [1]], ['two', [2]]])) {
    throw new Error(`unexpected start states: ${JSON.stringify(states)}`);
  }
});

Deno.test('Program API applies the event from a StepRequest', async () => {
  const builder = new flatbuffers.Builder(128);
  const request = new Program.StepRequestT(
    new Common.UUIDT(1n, 2n),
    [1, 2, 3],
    Events.Event.PuterPlaced,
    new Events.PuterPlacedT(
      new Common.IdentifierT('minecraft', 'overworld'),
      new Common.Vec3T(1, 2, 3),
    ),
  );
  builder.finish(request.pack(builder));

  const response = await handleProgramApi(
    new Request('http://localhost/nextState', {
      method: 'POST',
      headers: { 'content-type': 'application/octet-stream' },
      body: new Blob([builder.asUint8Array().slice().buffer as ArrayBuffer]),
    }),
  );
  if (response.status !== 200) {
    throw new Error(`expected status 200, got ${response.status}`);
  }

  const decoded = Program.StepResponse.getRootAsStepResponse(
    new flatbuffers.ByteBuffer(new Uint8Array(await response.arrayBuffer())),
  ).unpack();
  if (JSON.stringify(decoded.state) !== JSON.stringify([1, 2, 3])) {
    throw new Error(`unexpected next state: ${JSON.stringify(decoded.state)}`);
  }
  if (decoded.effects.length !== 1 || !(decoded.effects[0] instanceof Effects.SendChatT)) {
    throw new Error('expected one SendChat effect');
  }
});
