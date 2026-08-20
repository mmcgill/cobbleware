import { Effects, Events } from '@cobbleware/core';
import { handle } from '../src/index.ts';

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
