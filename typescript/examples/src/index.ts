import { create, Effects, Program, start, Common } from '@cobbleware/core';

const { DyeColor } = Common;

const HELLO = 1;
const ECHO = 2;
const HANG = 3;

export const helloHandler: Program = function handle(state, event) {
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

export const echoHandler: Program = function handle(state, event) {
  let effects: Effects.Effect[] = [];
  if (event.event.case === 'chatMessage') {
    state[1]++;
    if (event.event.value.message == 'off') {
      effects = [create(Effects.EffectSchema, {
        effect: {
          case: 'setRedstonePower',
          value: create(Effects.SetRedstonePowerSchema, {
            observerColor: DyeColor.BLACK,
            powerLevel: 0,
          }),
        }
      })];
    } else if (event.event.value.message == 'on') {
      effects = [create(Effects.EffectSchema, {
        effect: {
          case: 'setRedstonePower',
          value: create(Effects.SetRedstonePowerSchema, {
            observerColor: DyeColor.BLACK,
            powerLevel: 15,
          }),
        }
      })];
    } else {
      effects = [create(Effects.EffectSchema, {
        effect: {
          case: 'sendChat',
          value: create(Effects.SendChatSchema, {
            msg: `Echo (${state[1]}): ${event.event.value.message}`,
          }),
        },
      })];
    }
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
  } else if (event.event.case === 'wirelessObserverPaired' || event.event.case == 'wirelessObserverUnpaired') {
    const ev = event.event.value;
    const msg = `${event.event.case}: ${ev.observerId} ${ev.observerColor} ${JSON.stringify(ev.observerPos)}`;
    effects = [create(Effects.EffectSchema, {
      effect: {
        case: 'sendChat',
        value: create(Effects.SendChatSchema, {
          msg,
        }),
      },
    })];
    if (event.event.case === 'wirelessObserverPaired' && ev.observerColor === DyeColor.BLACK) {
      const { observerId } = event.event.value;
      effects.push(create(Effects.EffectSchema, {
        effect: {
          case: 'setRedstonePower',
          value: create(Effects.SetRedstonePowerSchema, {
            observerId,
            powerLevel: 15,
          })
        }
      }));
    }
  }
  return create(Program.StepResponseSchema, { state: state.slice(), effects });
};

export const hangHandler: Program = function handle(state, event) {
  if (event.event.case === 'started') {
    while (true) {
      // Intentionally never completes; used to test Main Worker robustness.
    }
  }
  return create(Program.StepResponseSchema, { state: state.slice(), effects: [] });
};

export const handle: Program = function handle(state, event) {
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
