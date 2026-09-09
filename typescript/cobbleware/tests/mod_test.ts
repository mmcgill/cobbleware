import { Common, create, Events, fromBinary, toBinary } from '../src/mod.ts';

Deno.test('PuterPlaced bindings round-trip through protobuf', () => {
  const event = create(Events.StartedSchema, { tick: 42n });
  const decoded = fromBinary(Events.StartedSchema, toBinary(Events.StartedSchema, event));

  if (decoded.tick !== 42n) throw new Error('tick did not round-trip');
});
