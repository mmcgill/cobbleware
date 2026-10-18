import {
  create,
  createProgramApiHandler,
  fromBinary,
  type Program,
  type StartStates,
} from '@cobbleware/core';
import { DyeColor } from '@cobbleware/core/common';
import {
  type Effect,
  EffectSchema,
  SendChatSchema,
  SetRedstonePowerSchema,
} from '@cobbleware/core/effects';
import { StepRequestSchema, StepResponseSchema } from '@cobbleware/core/program';
import { CONWAY, conwayHandler, conwayStartState } from './conway.ts';
import { BIGSTATE, bigstateHandler, bigstateStartState } from './bigstate.ts';

const HELLO = 1;
const ECHO = 2;
const HANG = 3;
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
  if (event.case === 'chatMessage') {
    state[1]++;
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
            msg: `Echo (${state[1]}) ${event.value.playerName}: ${event.value.message}`,
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
  return create(StepResponseSchema, { state: state.slice(), effects });
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

export const program: Program = function handle(state, event, context) {
  switch (state[0]) {
    case HELLO:
      return helloHandler(state, event, context);
    case ECHO:
      return echoHandler(state, event, context);
    case HANG:
      return hangHandler(state, event, context);
    case CONWAY:
      return conwayHandler(state, event, context);
    case BIGSTATE:
      return bigstateHandler(state, event, context);
    default:
      return create(StepResponseSchema, { state: state.slice(), effects: [] });
  }
};

export const startStates: StartStates = {
  hello: new Uint8Array([HELLO]),
  echo: new Uint8Array([ECHO, 0]),
  hang: new Uint8Array([HANG]),
  conway: conwayStartState,
  bigstate: bigstateStartState,
  bigresponse: new Uint8Array([BIGRESPONSE]),
};

export function createExampleProgramApiHandler(): (request: Request) => Promise<Response> {
  const handler = createProgramApiHandler({ program, startStates });
  return async (request) => {
    if (request.method === 'POST' && new URL(request.url).pathname === '/nextState') {
      try {
        const step = fromBinary(
          StepRequestSchema,
          new Uint8Array(await request.clone().arrayBuffer()),
        );
        if (step.state[0] === BIGRESPONSE && step.event?.event.case === 'chatMessage') {
          return new Response(unboundedResponseBytes(), {
            headers: { 'content-type': 'application/octet-stream' },
          });
        }
      } catch {
        // Let the standard Program API handler report malformed requests.
      }
    }
    return await handler(request);
  };
}

function unboundedResponseBytes(): ReadableStream<Uint8Array> {
  return new ReadableStream({
    pull(controller) {
      controller.enqueue(new Uint8Array(BIGRESPONSE_CHUNK_BYTES));
    },
  });
}

if (import.meta.main) {
  Deno.serve(createExampleProgramApiHandler());
}
