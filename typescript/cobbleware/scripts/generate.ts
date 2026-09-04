const packageDirectory = new URL('../', import.meta.url);
const workspaceDirectory = new URL('../', packageDirectory);
const generatedDirectory = new URL('./generated/', packageDirectory);
const temporaryGeneratedDirectory = new URL('./.generated-next/', packageDirectory);
const protobufDirectory = new URL('../../../protobuf/', import.meta.url);
const schemaFiles = [
  'common.proto',
  'effects.proto',
  'events.proto',
  'internal.proto',
  'program.proto',
];

function filesystemPath(url: URL): string {
  const path = decodeURIComponent(url.pathname);
  return Deno.build.os === 'windows' ? path.replace(/^\/([A-Za-z]:)/, '$1') : path;
}

const pluginName = Deno.build.os === 'windows' ? 'protoc-gen-es.cmd' : 'protoc-gen-es';
const plugin = new URL(`./node_modules/.bin/${pluginName}`, workspaceDirectory);

await Deno.remove(temporaryGeneratedDirectory, { recursive: true }).catch((error: unknown) => {
  if (error instanceof Deno.errors.NotFound) return;
  throw error;
});
await Deno.mkdir(temporaryGeneratedDirectory);

const result = await new Deno.Command('protoc', {
  args: [
    `--proto_path=${filesystemPath(protobufDirectory)}`,
    `--plugin=protoc-gen-es=${filesystemPath(plugin)}`,
    `--es_out=target=ts,import_extension=ts:${filesystemPath(temporaryGeneratedDirectory)}`,
    ...schemaFiles,
  ],
  stdout: 'inherit',
  stderr: 'inherit',
}).output();

if (!result.success) {
  throw new Error(`protoc exited with status ${result.code}`);
}

await Deno.remove(generatedDirectory, { recursive: true }).catch((error: unknown) => {
  if (error instanceof Deno.errors.NotFound) return;
  throw error;
});
await Deno.rename(temporaryGeneratedDirectory, generatedDirectory);
