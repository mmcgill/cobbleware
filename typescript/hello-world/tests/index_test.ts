import { Common, create, Events } from '@cobbleware/core';
import { handle } from '../src/index.ts';

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
