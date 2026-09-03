import * as flatbuffers from 'flatbuffers';
import { Effects, Events, Program } from '@cobbleware/core';
import { handle, handleProgramApi } from '../src/index.ts';

Deno.test('handle preserves state and emits Hello world', () => {
  const state = new Uint8Array([1, 2, 3]);
  const output = handle(state, Events.Event.PuterPlaced);

  if (output.nextState !== state) {
    throw new Error('handler changed the state');
  }

  if (output.effects.length !== 1) {
    throw new Error(`expected one effect, got ${output.effects.length}`);
  }

  const [effect] = output.effects;
  if (!(effect instanceof Effects.SendChatT) || effect.msg !== 'Hello world') {
    throw new Error(`unexpected effect: ${JSON.stringify(effect)}`);
  }
});

Deno.test('Program API returns the hello-world start states', async () => {
  const response = handleProgramApi(new Request('http://localhost/startStates'));
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
