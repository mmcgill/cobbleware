import { Common, create, Events, fromBinary, toBinary } from '../src/mod.ts';

Deno.test('PuterPlaced bindings round-trip through protobuf', () => {
  const event = create(Events.PuterPlacedSchema, {
    levelKey: create(Common.IdentifierSchema, { ns: 'minecraft', path: 'overworld' }),
    pos: create(Common.Vec3Schema, { x: 12, y: 64, z: -4 }),
  });
  const decoded = fromBinary(Events.PuterPlacedSchema, toBinary(Events.PuterPlacedSchema, event));

  if (decoded.levelKey?.path !== 'overworld') throw new Error('identifier did not round-trip');
  if (decoded.pos?.y !== 64) throw new Error('position did not round-trip');
});
