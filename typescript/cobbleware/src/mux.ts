import type { Event } from '@cobbleware/core/events';
import type { StepResponse } from '@cobbleware/core/program';
import type { Program, ProgramState, StartOpts, StepContext } from './program_api.ts';

const TAG_LENGTH = 20;

export type NamedPrograms = {
  [programName: string]: Program;
};

type ProgramEntry = {
  name: string;
  program: Program;
  tag: Uint8Array;
};

/**
 * Creates a Program that multiplexes named programs.
 *
 * Each supplied name becomes a start-state name. Its named program initially receives an empty
 * state. Thereafter, this program appends a 20-byte SHA-1 tag for that name to the named program's
 * state, routing future steps back to the same program. The tag consumes 20 bytes of the returned
 * state budget.
 *
 * @param programs a set of named Program functions
 * @returns a promise for start states and a multiplexing Program suitable for use with {@link start}
 */
export async function muxedProgram(programs: NamedPrograms): Promise<Required<Pick<StartOpts, "startStates"|"program">>> {
  const entries = await Promise.all(
    Object.keys(programs).map(async (name) => ({
      name,
      program: programs[name],
      tag: await sha1(new TextEncoder().encode(name)),
    })),
  );

  const startStates: Record<string, ProgramState> = {};
  for (const entry of entries) {
    startStates[entry.name] = new Uint8Array(entry.tag);
  }

  return {
    startStates,
    program: (state, event, context) => step(entries, state, event, context),
  };
}

function step(
  entries: readonly ProgramEntry[],
  state: ProgramState,
  event: Event,
  context: StepContext,
): StepResponse {
  if (state.byteLength < TAG_LENGTH) {
    throw new Error('Muxed program state is missing its 20-byte program tag');
  }
  if (context.stateSizeLimit < BigInt(TAG_LENGTH)) {
    throw new Error(
      'Muxed program requires at least 20 bytes of state capacity for its program tag',
    );
  }

  const entry = entries.find(({ tag }) => stateHasTag(state, tag));
  if (entry === undefined) {
    throw new Error('Muxed program state has an unknown program tag');
  }

  const internalState = new Uint8Array(
    state.buffer,
    state.byteOffset,
    state.byteLength - TAG_LENGTH,
  );
  const response = entry.program(internalState, event, {
    ...context,
    stateSizeLimit: context.stateSizeLimit - BigInt(TAG_LENGTH),
  });
  response.state = appendTag(response.state, entry.tag);
  return response;
}

function stateHasTag(state: ProgramState, tag: Uint8Array): boolean {
  const tagOffset = state.byteLength - TAG_LENGTH;
  for (let i = 0; i < TAG_LENGTH; i++) {
    if (state[tagOffset + i] !== tag[i]) return false;
  }
  return true;
}

function appendTag(state: ProgramState, tag: Uint8Array): ProgramState {
  const taggedState = new Uint8Array(state.byteLength + TAG_LENGTH);
  taggedState.set(state);
  taggedState.set(tag, state.byteLength);
  return taggedState;
}

async function sha1(input: Uint8Array): Promise<Uint8Array> {
  const bytes = new Uint8Array(input);
  return new Uint8Array(await crypto.subtle.digest('SHA-1', bytes.buffer as ArrayBuffer));
}
