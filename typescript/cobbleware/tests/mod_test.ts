import { create, fromBinary, toBinary, toJson } from '../src/mod.ts';
import {
  Direction,
  DyeColor,
  type GlobalPos,
  GlobalPosSchema,
  IdentifierSchema,
  Vec3iSchema,
} from '../generated/common_pb.ts';
import {
  BlockInfoSchema,
  BlockStateChangeSchema,
  EventSchema,
  StartedSchema,
} from '../generated/events_pb.ts';

Deno.test('Started bindings round-trip through protobuf', () => {
  const event = create(StartedSchema, { tick: 42n });
  const decoded = fromBinary(StartedSchema, toBinary(StartedSchema, event));

  if (decoded.tick !== 42n) throw new Error('tick did not round-trip');
});

Deno.test('BlockStateChange bindings round-trip through Event', () => {
  const event = create(EventSchema, {
    event: {
      case: 'blockStateChange',
      value: create(BlockStateChangeSchema, {
        observerColor: DyeColor.LIGHT_BLUE,
        observerDir: Direction.NORTH,
        observerPos: globalPos('minecraft', 'overworld', 12, 64, -8),
        blockPos: globalPos('minecraft', 'overworld', 12, 64, -9),
        oldState: create(BlockInfoSchema, {
          blockType: create(IdentifierSchema, { ns: 'minecraft', path: 'oak_door' }),
          properties: { facing: 'north', open: 'false' },
        }),
        newState: create(BlockInfoSchema, {
          blockType: create(IdentifierSchema, { ns: 'minecraft', path: 'oak_door' }),
          properties: { facing: 'north', open: 'true' },
        }),
      }),
    },
  });
  const decoded = fromBinary(EventSchema, toBinary(EventSchema, event));

  if (decoded.event.case !== 'blockStateChange') throw new Error('event case did not round-trip');
  if (decoded.event.value.observerColor !== DyeColor.LIGHT_BLUE) {
    throw new Error('observer color did not round-trip');
  }
  if (decoded.event.value.observerDir !== Direction.NORTH) {
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
  const event = create(BlockStateChangeSchema, {});
  const decoded = fromBinary(
    BlockStateChangeSchema,
    toBinary(BlockStateChangeSchema, event),
  );

  if (decoded.observerColor !== DyeColor.UNSPECIFIED) {
    throw new Error('observer color should default to unspecified');
  }
  if (decoded.observerDir !== Direction.UNSPECIFIED) {
    throw new Error('observer direction should default to unspecified');
  }

  const json = toJson(
    BlockStateChangeSchema,
    create(BlockStateChangeSchema, {
      observerColor: DyeColor.LIGHT_BLUE,
      observerDir: Direction.NORTH,
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

function globalPos(ns: string, path: string, x: number, y: number, z: number): GlobalPos {
  return create(GlobalPosSchema, {
    dimension: create(IdentifierSchema, { ns, path }),
    position: create(Vec3iSchema, { x, y, z }),
  });
}
