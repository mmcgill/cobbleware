import {
  create,
  defaultStepHandler,
  muxedProgram,
  type Program,
  start,
  type StartStates,
} from '@cobbleware/core';
import { DyeColor } from '@cobbleware/core/common';
import {
  type Effect,
  EffectSchema,
  SendChatSchema,
  SetRedstonePowerSchema,
} from '@cobbleware/core/effects';
import { type StepRequest, StepResponseSchema } from '@cobbleware/core/program';
import { conwayHandler } from './conway.ts';
import { bigstateHandler } from './bigstate.ts';

const BIGRESPONSE = 6;
const BIGRESPONSE_CHUNK_BYTES = 64 * 1024;

export const helloHandler: Program = function handle(state, { event }) {
  let effects: Effect[] = [];
  if (event.case === 'started') {
    effects = [create(EffectSchema, {
      effect: {
        case: 'sendChat',
        value: create(SendChatSchema, { msg: 'Hello world' }),
      },
    })];
  }
  return create(StepResponseSchema, { state: state.slice(), effects });
};

export const echoHandler: Program = function handle(state, { event }) {
  let effects: Effect[] = [];
  let nextState = state.slice();
  if (event.case === 'chatMessage') {
    const messageCount = (state[0] ?? 0) + 1;
    nextState = new Uint8Array([messageCount]);
    if (event.value.message == 'off') {
      effects = [create(EffectSchema, {
        effect: {
          case: 'setRedstonePower',
          value: create(SetRedstonePowerSchema, {
            observerColor: DyeColor.BLACK,
            powerLevel: 0,
          }),
        },
      })];
    } else if (event.value.message == 'on') {
      effects = [create(EffectSchema, {
        effect: {
          case: 'setRedstonePower',
          value: create(SetRedstonePowerSchema, {
            observerColor: DyeColor.BLACK,
            powerLevel: 15,
          }),
        },
      })];
    } else {
      effects = [create(EffectSchema, {
        effect: {
          case: 'sendChat',
          value: create(SendChatSchema, {
            msg: `Echo (${messageCount}) ${event.value.playerName}: ${event.value.message}`,
          }),
        },
      })];
    }
  } else if (event.case === 'blockStateChange') {
    const v = event.value;
    const props = JSON.stringify(v.newState?.properties);
    const msg =
      `Observer colored ${v.observerColor} saw block change: ${v.newState?.blockType?.path} ${props}`;
    console.info(msg);
  } else if (
    event.case === 'wirelessObserverPaired' || event.case == 'wirelessObserverUnpaired'
  ) {
    const ev = event.value;
    const msg = `${event.case}: ${ev.observerId} ${ev.observerColor} ${
      JSON.stringify(ev.observerPos)
    }`;
    console.info(msg);
    if (event.case === 'wirelessObserverPaired' && ev.observerColor === DyeColor.BLACK) {
      const { observerId } = event.value;
      effects.push(create(EffectSchema, {
        effect: {
          case: 'setRedstonePower',
          value: create(SetRedstonePowerSchema, {
            observerId,
            powerLevel: 15,
          }),
        },
      }));
    }
  }
  return create(StepResponseSchema, { state: nextState, effects });
};

export const hangHandler: Program = function handle(state, event) {
  console.log(`hangHandler: ${JSON.stringify(event.event.case)}`);
  if (event.event.case === 'chatMessage' && event.event.value.message === 'hang') {
    while (true) {
      // Intentionally never completes; used to test Main Worker robustness.
    }
  }
  return create(StepResponseSchema, { state: state.slice(), effects: [] });
};

const muxed = await muxedProgram({
  hello: helloHandler,
  echo: echoHandler,
  hang: hangHandler,
  conway: conwayHandler,
  bigstate: bigstateHandler,
});

export const program: Program = muxed.program;
export const startStates: StartStates = {
  ...muxed.startStates,
  bigresponse: new Uint8Array([BIGRESPONSE]),
};

const muxedStepHandler = defaultStepHandler(program);
const bigresponseStepHandler = defaultStepHandler((state) =>
  create(StepResponseSchema, { state: state.slice(), effects: [] })
);

export function stepHandler(request: StepRequest): Promise<Response> {
  const { state, event } = request;
  if (isBigResponseState(state) && event?.event.case === 'chatMessage') {
    return Promise.resolve(
      new Response(unboundedResponseBytes(), {
        headers: { 'content-type': 'application/octet-stream' },
      }),
    );
  }
  return isBigResponseState(state) ? bigresponseStepHandler(request) : muxedStepHandler(request);
}

function isBigResponseState(state: Uint8Array): boolean {
  return state.byteLength === 1 && state[0] === BIGRESPONSE;
}

function unboundedResponseBytes(): ReadableStream<Uint8Array> {
  return new ReadableStream({
    pull(controller) {
      controller.enqueue(new Uint8Array(BIGRESPONSE_CHUNK_BYTES));
    },
  });
}

if (import.meta.main) {
  start({ startStates, stepHandler });
}
