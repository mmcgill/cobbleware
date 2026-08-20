const packageDirectory = new URL('../', import.meta.url);
const generatedDirectory = new URL('./generated/', packageDirectory);
const schema = new URL('../../schema/cobbleware.fbs', packageDirectory);

function filesystemPath(url: URL): string {
  const path = decodeURIComponent(url.pathname);
  return Deno.build.os === 'windows' ? path.replace(/^\/([A-Za-z]:)/, '$1') : path;
}

const result = await new Deno.Command('flatc', {
  args: [
    '--ts',
    '--gen-all',
    '--gen-object-api',
    '--filename-suffix',
    '_generated',
    '-o',
    filesystemPath(generatedDirectory),
    filesystemPath(schema),
  ],
  stdout: 'inherit',
  stderr: 'inherit',
}).output();

if (!result.success) {
  throw new Error(`flatc exited with status ${result.code}`);
}

async function normalizeImports(directory: URL): Promise<void> {
  for await (const entry of Deno.readDir(directory)) {
    const path = new URL(entry.name, directory);
    if (entry.isDirectory) {
      await normalizeImports(new URL(`${entry.name}/`, directory));
    } else if (entry.isFile && entry.name.endsWith('.ts')) {
      const source = await Deno.readTextFile(path);
      await Deno.writeTextFile(path, source.replaceAll(".js'", ".ts'").replaceAll('.js"', '.ts"'));
    }
  }
}
await normalizeImports(generatedDirectory);
