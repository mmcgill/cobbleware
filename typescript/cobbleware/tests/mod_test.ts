import { Common, create, Events, fromBinary, toBinary, toJson } from '../src/mod.ts';

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
  if (decoded.event.value.observerDir !== Common.Direction.NORTH) {
    throw new Error('observer direction did not round-trip');
  }
  if (decoded.event.value.observerPos?.position?.x !== 12) {
    throw new Error('observer position did not round-trip');
  }
  if (
    decoded.event.value.oldState?.properties.open !== 'false' ||
    decoded.event.value.newState?.properties.open !== 'true'
  ) {
    throw new Error('block-state properties did not round-trip');
  }
});

Deno.test('Wireless Observer event enums default to unspecified and use canonical ProtoJSON names', () => {
  const event = create(Events.BlockStateChangeSchema, {});
  const decoded = fromBinary(
    Events.BlockStateChangeSchema,
    toBinary(Events.BlockStateChangeSchema, event),
  );

  if (decoded.observerColor !== Common.DyeColor.UNSPECIFIED) {
    throw new Error('observer color should default to unspecified');
  }
  if (decoded.observerDir !== Common.Direction.UNSPECIFIED) {
    throw new Error('observer direction should default to unspecified');
  }

  const json = toJson(
    Events.BlockStateChangeSchema,
    create(Events.BlockStateChangeSchema, {
      observerColor: Common.DyeColor.LIGHT_BLUE,
      observerDir: Common.Direction.NORTH,
    }),
  );
  if (
    typeof json !== 'object' ||
    json === null ||
    Array.isArray(json) ||
    json.observerColor !== 'DYE_COLOR_LIGHT_BLUE' ||
    json.observerDir !== 'DIRECTION_NORTH'
  ) {
    throw new Error('observer enums should use their canonical ProtoJSON names');
  }
});

function globalPos(ns: string, path: string, x: number, y: number, z: number): Common.GlobalPos {
  return create(Common.GlobalPosSchema, {
    dimension: create(Common.IdentifierSchema, { ns, path }),
    position: create(Common.Vec3iSchema, { x, y, z }),
  });
}
