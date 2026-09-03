import * as flatbuffers from 'flatbuffers';
import { Program } from '@cobbleware/core';
import { unpackStartStates } from '../src/programs/start-states.ts';

function encodeStartStates(states: Program.StartStateT[]): Uint8Array {
  const builder = new flatbuffers.Builder(64);
  const root = new Program.StartStatesResponseT(states).pack(builder);
  builder.finish(root);
  return builder.asUint8Array();
}

Deno.test('Start States FlatBuffers decode to JSON-compatible byte arrays', () => {
  const decoded = unpackStartStates(
    encodeStartStates([
      new Program.StartStateT('one', [1]),
      new Program.StartStateT('two', [2]),
    ]),
  );

  if (JSON.stringify(decoded) !== JSON.stringify({ one: [1], two: [2] })) {
    throw new Error(`unexpected decoded start states: ${JSON.stringify(decoded)}`);
  }
});

Deno.test('Start States FlatBuffers decode rejects repeated names', () => {
  try {
    unpackStartStates(
      encodeStartStates([
        new Program.StartStateT('one', [1]),
        new Program.StartStateT('one', [2]),
      ]),
    );
    throw new Error('duplicate state names should be rejected');
  } catch (error) {
    if (!(error instanceof Error) || !error.message.includes('repeated')) {
      throw error;
    }
  }
});

Deno.test('Start States FlatBuffers decode rejects malformed payloads', () => {
  try {
    unpackStartStates(new Uint8Array([1, 2, 3]));
    throw new Error('malformed payload should be rejected');
  } catch (error) {
    if (!(error instanceof Error) || !error.message.includes('invalid')) {
      throw error;
    }
  }
});
