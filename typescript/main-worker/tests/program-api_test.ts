import { Common, create, Events, fromBinary, Program, toBinary } from '@cobbleware/core';
import {
  invokeNextState,
  invokeStartStates,
  PROGRAM_BINARY_CONTENT_TYPE,
} from '../src/programs/program-api.ts';

function worker(fetch: CobblewareUserWorker['fetch']): CobblewareUserWorker {
  return { closed: Promise.resolve({}), retire: () => {}, fetch };
}

Deno.test('Program API startStates invokes the expected endpoint', async () => {
  const expected = { default: [1, 2, 3] };
  let received: Request | undefined;
  const startStates = await invokeStartStates(worker((request) => {
    received = request;
    return Promise.resolve(binaryResponse(
      toBinary(
        Program.StartStatesResponseSchema,
        create(Program.StartStatesResponseSchema, {
          startStates: Object.entries(expected).map(([stateName, state]) =>
            create(Program.StartStateSchema, { stateName, state: new Uint8Array(state) })
          ),
        }),
      ),
    ));
  }));

  if (received?.method !== 'GET' || received.url !== 'http://cobbleware.local/startStates') {
    throw new Error('startStates request was not constructed correctly');
  }
  if (JSON.stringify(startStates) !== JSON.stringify(expected)) {
    throw new Error('startStates response was not decoded correctly');
  }
});

Deno.test('Program API nextState serializes its request and deserializes its response', async () => {
  const expectedRequest = create(Program.StepRequestSchema, {
    executionId: create(Common.UuidSchema, { high: 1n, low: 2n }),
    state: new Uint8Array([1, 2, 3]),
    event: create(Events.EventSchema, {
      event: { case: 'started', value: create(Events.StartedSchema, { tick: 42n }) },
    }),
    effectLimit: 5,
    stateSizeLimit: 1024n,
  });
  const expectedResponse = create(Program.StepResponseSchema, {
    state: new Uint8Array([9, 8, 7]),
  });
  let received: Request | undefined;
  const response = await invokeNextState(
    worker((request) => {
      received = request;
      return Promise.resolve(
        binaryResponse(toBinary(Program.StepResponseSchema, expectedResponse)),
      );
    }),
    expectedRequest,
  );

  if (received?.method !== 'POST' || received.url !== 'http://cobbleware.local/nextState') {
    throw new Error('nextState request was not constructed correctly');
  }
  if (received.headers.get('content-type') !== PROGRAM_BINARY_CONTENT_TYPE) {
    throw new Error('nextState request has the wrong content type');
  }
  const request = fromBinary(
    Program.StepRequestSchema,
    new Uint8Array(await received.arrayBuffer()),
  );
  if (JSON.stringify([...request.state]) !== JSON.stringify([...expectedRequest.state])) {
    throw new Error('nextState request was not serialized correctly');
  }
  if (
    request.effectLimit !== expectedRequest.effectLimit ||
    request.stateSizeLimit !== expectedRequest.stateSizeLimit
  ) {
    throw new Error('nextState request fields changed during serialization');
  }
  if (
    request.executionId?.high !== 1n || request.executionId.low !== 2n ||
    request.event?.event.case !== 'started'
  ) {
    throw new Error('nextState request nested messages were not serialized correctly');
  }
  if (JSON.stringify([...response.state]) !== JSON.stringify([...expectedResponse.state])) {
    throw new Error('nextState response was not deserialized correctly');
  }
});

function binaryResponse(body: Uint8Array): Response {
  return new Response(new Blob([body.slice().buffer as ArrayBuffer]), {
    headers: { 'content-type': PROGRAM_BINARY_CONTENT_TYPE },
  });
}
