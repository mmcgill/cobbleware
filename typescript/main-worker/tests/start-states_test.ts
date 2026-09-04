import { create, Program, toBinary } from '@cobbleware/core';
import { unpackStartStates } from '../src/programs/start-states.ts';

function encodeStartStates(states: Program.StartState[]): Uint8Array {
  return toBinary(
    Program.StartStatesResponseSchema,
    create(Program.StartStatesResponseSchema, { startStates: states }),
  );
}

Deno.test('Start States protobuf messages decode to JSON-compatible byte arrays', () => {
  const decoded = unpackStartStates(
    encodeStartStates([
      create(Program.StartStateSchema, { stateName: 'one', state: new Uint8Array([1]) }),
      create(Program.StartStateSchema, { stateName: 'two', state: new Uint8Array([2]) }),
    ]),
  );

  if (JSON.stringify(decoded) !== JSON.stringify({ one: [1], two: [2] })) {
    throw new Error(`unexpected decoded start states: ${JSON.stringify(decoded)}`);
  }
});

Deno.test('Start States protobuf messages reject repeated names', () => {
  try {
    unpackStartStates(
      encodeStartStates([
        create(Program.StartStateSchema, { stateName: 'one', state: new Uint8Array([1]) }),
        create(Program.StartStateSchema, { stateName: 'one', state: new Uint8Array([2]) }),
      ]),
    );
    throw new Error('duplicate state names should be rejected');
  } catch (error) {
    if (!(error instanceof Error) || !error.message.includes('repeated')) {
      throw error;
    }
  }
});

Deno.test('Start States protobuf messages reject malformed payloads', () => {
  try {
    unpackStartStates(new Uint8Array([1, 2, 3]));
    throw new Error('malformed payload should be rejected');
  } catch (error) {
    if (!(error instanceof Error) || !error.message.includes('invalid')) {
      throw error;
    }
  }
});
