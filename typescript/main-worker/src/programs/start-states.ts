/// <reference path="../runtime.d.ts" />

import * as flatbuffers from 'flatbuffers';
import { Program } from '@cobbleware/core';
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
    validateFlatBufferRoot(content);
    const root = Program.StartStatesResponse.getRootAsStartStatesResponse(
      new flatbuffers.ByteBuffer(content),
    );
    const startStates: StartStates = {};

    for (let index = 0; index < root.startStatesLength(); index++) {
      const startState = root.startStates(index);
      if (startState === null) {
        throw new Error(`start state ${index} is missing`);
      }

      const stateName = startState.stateName();
      const state = startState.stateArray();
      if (stateName === null || state === null) {
        throw new Error(`start state ${index} is missing a required field`);
      }
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

function validateFlatBufferRoot(content: Uint8Array): void {
  const view = new DataView(content.buffer, content.byteOffset, content.byteLength);
  if (content.byteLength < 4) {
    throw new Error('FlatBuffers body is too short');
  }

  const rootOffset = view.getInt32(0, true);
  if (rootOffset < 4 || rootOffset + 4 > content.byteLength) {
    throw new Error('FlatBuffers root table is out of bounds');
  }

  const tableOffset = view.getInt32(rootOffset, true);
  const vtableOffset = rootOffset - tableOffset;
  if (tableOffset <= 0 || vtableOffset < 0 || vtableOffset + 4 > content.byteLength) {
    throw new Error('FlatBuffers vtable is out of bounds');
  }

  const vtableLength = view.getUint16(vtableOffset, true);
  const objectLength = view.getUint16(vtableOffset + 2, true);
  if (
    vtableLength < 4 ||
    vtableOffset + vtableLength > content.byteLength ||
    rootOffset + objectLength > content.byteLength
  ) {
    throw new Error('FlatBuffers table is out of bounds');
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
