/// <reference path="../runtime.d.ts" />

import { fromBinary, Program } from '@cobbleware/core';
import { jsonResponse } from '../auth/http.ts';
import type { ActiveProgram } from './types.ts';

export const PROGRAM_BINARY_CONTENT_TYPE = 'application/octet-stream';

const START_STATES_PATH = 'http://cobbleware.local/startStates';
const NEXT_STATE_PATH = 'http://cobbleware.local/nextState';
const START_STATES_CONTENT_TYPE = PROGRAM_BINARY_CONTENT_TYPE;

export type StartStates = Record<string, number[]>;

export type ProgramRunner = {
  startStates(request: Request, playerUUID: string, program: ActiveProgram): Promise<StartStates>;
  step(
    request: Request,
    playerUUID: string,
    program: ActiveProgram,
    body: Uint8Array,
  ): Promise<Response>;
};

export class RuntimeProgramRunner implements ProgramRunner {
  async startStates(
    request: Request,
    playerUUID: string,
    program: ActiveProgram,
  ): Promise<StartStates> {
    const worker = await EdgeRuntime.userWorkers.create({
      servicePath: workerServicePath(playerUUID, program.metadata.id),
      maybeEszip: program.content,
      maybeEntrypoint: 'file:///src/index.ts',
    });

    const programRequest = new Request(START_STATES_PATH, { method: 'GET' });
    EdgeRuntime.applySupabaseTag(request, programRequest);
    const response = await worker.fetch(programRequest);
    return await decodeStartStatesResponse(response);
  }

  async step(
    request: Request,
    playerUUID: string,
    program: ActiveProgram,
    body: Uint8Array,
  ): Promise<Response> {
    const worker = await EdgeRuntime.userWorkers.create({
      servicePath: workerServicePath(playerUUID, program.metadata.id),
      maybeEszip: program.content,
      maybeEntrypoint: 'file:///src/index.ts',
    });

    const programRequest = new Request(NEXT_STATE_PATH, {
      method: 'POST',
      headers: { 'content-type': PROGRAM_BINARY_CONTENT_TYPE },
      body: new Blob([body.slice().buffer as ArrayBuffer]),
    });
    EdgeRuntime.applySupabaseTag(request, programRequest);
    return await worker.fetch(programRequest);
  }
}

export async function decodeStartStatesResponse(response: Response): Promise<StartStates> {
  if (response.status !== 200) {
    throw new Error(`program returned HTTP ${response.status} for /startStates`);
  }

  const contentType = response.headers.get('content-type')?.split(';', 1)[0].trim().toLowerCase();
  if (contentType !== START_STATES_CONTENT_TYPE) {
    throw new Error('program returned an unexpected /startStates content type');
  }

  const content = new Uint8Array(await response.arrayBuffer());
  return unpackStartStates(content);
}

export function programErrorResponse(error: unknown): Response {
  return jsonResponse({ programError: describeProgramError(error) });
}

export function unpackStartStates(content: Uint8Array): StartStates {
  try {
    const root = fromBinary(Program.StartStatesResponseSchema, content);
    const startStates: StartStates = {};

    for (const [index, startState] of root.startStates.entries()) {
      const { stateName, state } = startState;
      if (Object.hasOwn(startStates, stateName)) {
        throw new Error(`start state name is repeated: ${stateName}`);
      }

      startStates[stateName] = [...state];
    }

    return startStates;
  } catch (error) {
    if (error instanceof Error && error.message.startsWith('start state')) {
      throw error;
    }
    throw new Error(
      `program returned an invalid /startStates response: ${describeProgramError(error)}`,
    );
  }
}

function workerServicePath(playerUUID: string, programId: string): string {
  return `cobbleware-${encodeURIComponent(playerUUID)}-${programId}`;
}

function describeProgramError(error: unknown): string {
  if (error instanceof Error) {
    return error.stack ?? error.message;
  }
  return String(error);
}
