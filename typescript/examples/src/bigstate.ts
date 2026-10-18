import type { Program } from '@cobbleware/core';
import { create } from '@bufbuild/protobuf';
import * as program from '@cobbleware/core/program';

const STATE_INCREASE_SIZE_BYTES = 1024 * 10;

export const bigstateHandler: Program = (state, { event }, _context) => {
  if (event.case === 'chatMessage' && event.value.message.toLowerCase() === 'bigger') {
    const biggerlength = state.length + STATE_INCREASE_SIZE_BYTES;
    console.log(`Enlarging state from ${state.length} bytes to ${biggerlength} bytes`);
    state = new Uint8Array(biggerlength);
  }
  return create(program.StepResponseSchema, { state, effects: [] });
};
