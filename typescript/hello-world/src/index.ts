import { create, Effects, Program, type Program as EventProgram, start } from '@cobbleware/core';
import { DyeColor, DyeColorSchema } from '../../cobbleware/generated/common_pb.ts';

const HELLO = 1;
const ECHO = 2;
const HANG = 3;

export const helloHandler: EventProgram = function handle(state, event) {
  let effects: Effects.Effect[] = [];
  if (event.event.case === 'started') {
    effects = [create(Effects.EffectSchema, {
      effect: {
        case: 'sendChat',
        value: create(Effects.SendChatSchema, { msg: 'Hello world' }),
      },
    })];
  } else if (event.event.case === 'resumed') {
    effects = [create(Effects.EffectSchema, {
      effect: {
        case: 'emitBeep',
        value: create(Effects.EmitBeepSchema, {}),
      },
    })];
  }
  return create(Program.StepResponseSchema, { state: state.slice(), effects });
};

export const echoHandler: EventProgram = function handle(state, event) {
  let effects: Effects.Effect[] = [];
  if (event.event.case === 'chatMessage') {
    state[1]++;
    effects = [create(Effects.EffectSchema, {
      effect: {
        case: 'sendChat',
        value: create(Effects.SendChatSchema, {
          msg: `Echo (${state[1]}): ${event.event.value.message}`,
        }),
      },
    })];
  } else if (event.event.case === 'blockStateChange') {
    const v = event.event.value;
    const props = JSON.stringify(v.newState?.properties);
    const msg = `Observer colored ${v.observerColor} saw block change: ${v.newState?.blockType?.path} ${props}`;
    effects = [create(Effects.EffectSchema, {
      effect: {
        case: 'sendChat',
        value: create(Effects.SendChatSchema, {
          msg,
        }),
      },
    })];
  }
  return create(Program.StepResponseSchema, { state: state.slice(), effects });
};

export const hangHandler: EventProgram = function handle(state, event) {
  if (event.event.case === 'started') {
    while (true) {
      // Intentionally never completes; used to test Main Worker robustness.
    }
  }
  return create(Program.StepResponseSchema, { state: state.slice(), effects: [] });
};

export const handle: EventProgram = function handle(state, event) {
  switch (state[0]) {
    case HELLO:
      return helloHandler(state, event);
    case ECHO:
      return echoHandler(state, event);
    case HANG:
      return hangHandler(state, event);
    default:
      return create(Program.StepResponseSchema, { state: state.slice(), effects: [] });
  }
};

export const startStates = {
  hello: new Uint8Array([HELLO]),
  echo: new Uint8Array([ECHO, 0]),
  hang: new Uint8Array([HANG]),
};

if (import.meta.main) {
  start({ program: handle, startStates });
}
