/**
 * Public entry point for Cobbleputer programs.
 */

import type { Event } from '../generated/cobbleware/events.ts';
import type { unionToEffect } from '../generated/cobbleware/effects/effect.ts';

export * as Common from '../generated/cobbleware/common.ts';
export * as Events from '../generated/cobbleware/events.ts';
export * as Effects from '../generated/cobbleware/effects.ts';
export * as Program from '../generated/cobbleware/program.ts';
export * from '../generated/cobbleware.ts';

export type ProgramState = Uint8Array;

/** Effect table values represented by the generated effect union. */
export type Effect = ReturnType<typeof unionToEffect>;
export type EffectT = Program.StepResponseT['effects'][number];

/**
 * A Program is an event handler that is called with the current value of a Cobbleputer state
 * and an Event, and returns the updated Cobbleputer state and a sequence of effects.
 */
export type Program = (state: ProgramState, event: Event) => Program.StepResponse;
