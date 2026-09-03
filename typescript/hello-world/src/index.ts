import * as flatbuffers from 'flatbuffers';
import { Effects, type EffectT, Program, type Program as EventProgram } from '@cobbleware/core';

const helloWorldEffect: EffectT = new Effects.SendChatT('Hello world');

export const handle: EventProgram = function handle(state, _event) {
  return {
    nextState: state,
    effects: [helloWorldEffect],
  };
};

const START_STATES_RESPONSE = createStartStatesResponse();

export function handleProgramApi(request: Request): Response {
  const { pathname } = new URL(request.url);
  if (pathname !== '/startStates') {
    return new Response(null, { status: 404 });
  }
  if (request.method !== 'GET') {
    return new Response(null, { status: 405, headers: { allow: 'GET' } });
  }

  return new Response(new Blob([START_STATES_RESPONSE.slice().buffer as ArrayBuffer]), {
    status: 200,
    headers: { 'content-type': 'application/octet-stream' },
  });
}

function createStartStatesResponse(): Uint8Array {
  const builder = new flatbuffers.Builder(64);
  const root = new Program.StartStatesResponseT([
    new Program.StartStateT('one', [1]),
    new Program.StartStateT('two', [2]),
  ]).pack(builder);
  builder.finish(root);
  return builder.asUint8Array();
}

if (import.meta.main) {
  Deno.serve(handleProgramApi);
}
