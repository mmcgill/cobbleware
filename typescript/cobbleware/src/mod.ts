/**
 * Public entry point for Cobbleputer programs.
 */

import type { Effect as GeneratedEffect } from '../generated/effects_pb.ts';
import type { Event } from '../generated/events_pb.ts';
import type { StepResponse } from '../generated/program_pb.ts';

export * as Common from '../generated/common_pb.ts';
export * as Events from '../generated/events_pb.ts';
export * as Effects from '../generated/effects_pb.ts';
export * as Program from '../generated/program_pb.ts';
export { create, fromBinary, toBinary } from '@bufbuild/protobuf';

export type ProgramState = Uint8Array;

/** An effect represented by the generated protobuf oneof. */
export type Effect = GeneratedEffect;
export type EffectT = GeneratedEffect;

/**
 * A Program is an event handler that is called with the current value of a Cobbleputer state
 * and an Event, and returns the updated Cobbleputer state and a sequence of effects.
 */
export type Program = (state: ProgramState, event: Event) => StepResponse;
