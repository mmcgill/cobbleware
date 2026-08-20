import * as flatbuffers from 'flatbuffers';
import { Common, Events } from '../src/mod.ts';

Deno.test('PuterPlaced bindings round-trip through FlatBuffers', () => {
  const builder = new flatbuffers.Builder(0);
  const event = new Events.PuterPlacedT(
    new Common.IdentifierT('minecraft', 'overworld'),
    new Common.Vec3T(12, 64, -4),
  );

  builder.finish(event.pack(builder));
  const decoded = Events.PuterPlaced.getRootAsPuterPlaced(
    new flatbuffers.ByteBuffer(builder.asUint8Array()),
  ).unpack();

  if (decoded.levelKey?.path !== 'overworld') throw new Error('identifier did not round-trip');
  if (decoded.pos?.y !== 64) throw new Error('position did not round-trip');
});
