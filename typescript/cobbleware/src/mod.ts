/**
 * Public entry point for Cobbleputer programs.
 */

import type { Event } from '../generated/cobbleware/events.ts';

export * as Common from '../generated/cobbleware/common.ts';
export * as Events from '../generated/cobbleware/events.ts';
export * as Effects from '../generated/cobbleware/effects.ts';


export type ProgramState = Uint8Array

/**
 * A Program is an event handler that is called with the current value of a Cobbleputer state
 * and an Event, and returns the updated Cobbleputer state.
 */
export type Program = (state:ProgramState, event:Event) => ProgramState;