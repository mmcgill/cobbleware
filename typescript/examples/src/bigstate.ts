import { Program, type Program as EventProgram } from '@cobbleware/core';
import { create } from '@bufbuild/protobuf';

const STATE_INCREASE_SIZE_BYTES = 1024 * 10;

export const BIGSTATE = 5;

export const bigstateStartState = new Uint8Array([BIGSTATE]);

export const bigstateHandler: EventProgram = (state, e, _context) => {
  const { event } = e;
  if (event.case === 'chatMessage' && event.value.message.toLowerCase() === 'bigger') {
    const biggerlength = state.length + STATE_INCREASE_SIZE_BYTES;
    console.log(`Enlarging state from ${state.length} bytes to ${biggerlength} bytes`);
    state = new Uint8Array(biggerlength);
    state[0] = BIGSTATE;
  }
  return create(Program.StepResponseSchema, { state, effects: [] });
};
