/**
 * Public entry point for Cobbleputer programs.
 */

import type { Event } from '../generated/cobbleware/events.ts';
import { unionToEffect } from '../generated/cobbleware/effects/effect.ts';
import type { ProgramOutputT } from '../generated/program-output.ts';

export * as Common from '../generated/cobbleware/common.ts';
export * as Events from '../generated/cobbleware/events.ts';
export * as Effects from '../generated/cobbleware/effects.ts';
export * as Program from '../generated/cobbleware/program.ts';
export * from '../generated/cobbleware.ts';

export type ProgramState = Uint8Array;

/** Effect table values represented by the generated effect union. */
export type Effect = ReturnType<typeof unionToEffect>;
export type EffectT = ProgramOutputT['effects'][number];

export interface ProgramOutput {
  nextState: ProgramState;
  effects: EffectT[];
}

/**
 * A Program is an event handler that is called with the current value of a Cobbleputer state
 * and an Event, and returns the updated Cobbleputer state.
 */
export type Program = (state: ProgramState, event: Event) => ProgramOutput;
