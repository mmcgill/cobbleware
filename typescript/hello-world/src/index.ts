import { create, Effects, Program, type Program as EventProgram, start } from '@cobbleware/core';

export const handle: EventProgram = function handle(state, event) {
  let effects: Effects.Effect[] = [];
  if (event.event.case === 'puterReset') {
    effects = [create(Effects.EffectSchema, {
      effect: {
        case: 'emitBeep',
        value: create(Effects.EmitBeepSchema, {}),
      },
    })];
  } else if (event.event.case === 'puterPlaced') {
    effects = [create(Effects.EffectSchema, {
      effect: {
        case: 'sendChat',
        value: create(Effects.SendChatSchema, { msg: 'Hello world' }),
      },
    })];
  }
  return create(Program.StepResponseSchema, { state: state.slice(), effects });
};

export const startStates = {
  one: new Uint8Array([1]),
  two: new Uint8Array([2]),
};

if (import.meta.main) {
  start({ program: handle, startStates });
}
