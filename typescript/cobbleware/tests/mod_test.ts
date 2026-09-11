import { Common, create, Events, fromBinary, toBinary } from '../src/mod.ts';

Deno.test('Started bindings round-trip through protobuf', () => {
  const event = create(Events.StartedSchema, { tick: 42n });
  const decoded = fromBinary(Events.StartedSchema, toBinary(Events.StartedSchema, event));

  if (decoded.tick !== 42n) throw new Error('tick did not round-trip');
});

Deno.test('BlockStateChange bindings round-trip through Event', () => {
  const event = create(Events.EventSchema, {
    event: {
      case: 'blockStateChange',
      value: create(Events.BlockStateChangeSchema, {
        observerColor: Common.DyeColor.LIGHT_BLUE,
        observerDir: Common.Direction.NORTH,
        observerPos: globalPos('minecraft', 'overworld', 12, 64, -8),
        blockPos: globalPos('minecraft', 'overworld', 12, 64, -9),
        oldState: create(Events.BlockInfoSchema, {
          blockType: create(Common.IdentifierSchema, { ns: 'minecraft', path: 'oak_door' }),
          properties: { facing: 'north', open: 'false' },
        }),
        newState: create(Events.BlockInfoSchema, {
          blockType: create(Common.IdentifierSchema, { ns: 'minecraft', path: 'oak_door' }),
          properties: { facing: 'north', open: 'true' },
        }),
      }),
    },
  });
  const decoded = fromBinary(Events.EventSchema, toBinary(Events.EventSchema, event));

  if (decoded.event.case !== 'blockStateChange') throw new Error('event case did not round-trip');
  if (decoded.event.value.observerColor !== Common.DyeColor.LIGHT_BLUE) {
    throw new Error('observer color did not round-trip');
  }
  if (decoded.event.value.observerPos?.position?.x !== 12) {
    throw new Error('observer position did not round-trip');
  }
  if (decoded.event.value.oldState?.properties.open !== 'false'
      || decoded.event.value.newState?.properties.open !== 'true') {
    throw new Error('block-state properties did not round-trip');
  }
});

function globalPos(ns: string, path: string, x: number, y: number, z: number): Common.GlobalPos {
  return create(Common.GlobalPosSchema, {
    dimension: create(Common.IdentifierSchema, { ns, path }),
    position: create(Common.Vec3iSchema, { x, y, z }),
  });
}
