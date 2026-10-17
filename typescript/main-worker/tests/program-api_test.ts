import { create, fromBinary, toBinary } from '@cobbleware/core';
import { UuidSchema } from '@cobbleware/core/common';
import { EventSchema, StartedSchema } from '@cobbleware/core/events';
import {
  StartStateSchema,
  StartStatesResponseSchema,
  StepRequest,
  StepRequestSchema,
  StepResponseSchema,
} from '@cobbleware/core/program';
import {
  invokeNextState,
  invokeStartStates,
  PROGRAM_BINARY_CONTENT_TYPE,
  StepResponseSizeLimitError,
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
        StartStatesResponseSchema,
        create(StartStatesResponseSchema, {
          startStates: Object.entries(expected).map(([stateName, state]) =>
            create(StartStateSchema, { stateName, state: new Uint8Array(state) })
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
  const expectedRequest = create(StepRequestSchema, {
    executionId: create(UuidSchema, { high: 1n, low: 2n }),
    state: new Uint8Array([1, 2, 3]),
    event: create(EventSchema, {
      event: { case: 'started', value: create(StartedSchema, { tick: 42n }) },
    }),
    effectLimit: 5,
    stateSizeLimit: 1024n,
  });
  const expectedResponse = create(StepResponseSchema, {
    state: new Uint8Array([9, 8, 7]),
  });
  let received: Request | undefined;
  const response = await invokeNextState(
    worker((request) => {
      received = request;
      return Promise.resolve(
        binaryResponse(toBinary(StepResponseSchema, expectedResponse)),
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
    StepRequestSchema,
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

Deno.test('Program API nextState accepts a streamed response at its size limit', async () => {
  const expected = create(StepResponseSchema, { state: new Uint8Array([9, 8, 7]) });
  const body = toBinary(StepResponseSchema, expected);
  const response = await invokeNextState(
    worker(() => Promise.resolve(streamingResponse([body.slice(0, 2), body.slice(2)]))),
    stepRequest(),
    BigInt(body.byteLength),
  );

  if (JSON.stringify([...response.state]) !== JSON.stringify([...expected.state])) {
    throw new Error('response at the size limit was not decoded');
  }
});

Deno.test('Program API nextState cancels an oversized streamed response', async () => {
  let cancelled = false;
  let error: unknown;
  try {
    await invokeNextState(
      worker(() =>
        Promise.resolve(streamingResponse(
          [new Uint8Array([1, 2]), new Uint8Array([3])],
          () => {
            cancelled = true;
          },
          500,
          false,
          'text/plain',
        ))
      ),
      stepRequest(),
      2n,
    );
  } catch (caught) {
    error = caught;
  }

  if (!(error instanceof StepResponseSizeLimitError)) {
    throw new Error(
      `oversized response did not report its size-limit error: ${
        error instanceof Error ? error.stack ?? error.message : String(error)
      }`,
    );
  }
  if (!cancelled) throw new Error('oversized response stream was not cancelled');
});

function stepRequest(): StepRequest {
  return create(StepRequestSchema, {
    executionId: create(UuidSchema, { high: 1n, low: 2n }),
    state: new Uint8Array([1]),
    event: create(EventSchema, {
      event: { case: 'started', value: create(StartedSchema, { tick: 1n }) },
    }),
    effectLimit: 1,
    stateSizeLimit: 1n,
  });
}

function binaryResponse(body: Uint8Array): Response {
  return new Response(new Blob([body.slice().buffer as ArrayBuffer]), {
    headers: { 'content-type': PROGRAM_BINARY_CONTENT_TYPE },
  });
}

function streamingResponse(
  chunks: Uint8Array[],
  onCancel?: () => void,
  status = 200,
  close = true,
  contentType = PROGRAM_BINARY_CONTENT_TYPE,
): Response {
  let index = 0;
  return new Response(
    new ReadableStream({
      pull(controller) {
        const chunk = chunks[index++];
        if (chunk === undefined) {
          if (close) controller.close();
        } else {
          controller.enqueue(chunk);
        }
      },
      cancel: onCancel,
    }),
    { status, headers: { 'content-type': contentType } },
  );
}
