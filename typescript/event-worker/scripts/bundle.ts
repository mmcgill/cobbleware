const defaultRuntimeBinary = Deno.build.os === 'windows'
  ? '../../mc-edge-runtime/target/debug/edge-runtime.exe'
  : '../../mc-edge-runtime/target/debug/edge-runtime';

const runtimeBinary = Deno.env.get('EDGE_RUNTIME_BIN') ?? defaultRuntimeBinary;
const programDirectory = await Deno.realPath(new URL('../', import.meta.url));
const outputPath = argumentValue('--output') ?? 'event-worker.eszip';
const temporaryDirectory = await Deno.makeTempDir({
  dir: programDirectory,
  prefix: '.cobbleware-eszip-',
});
const bundledProgramPath = `${temporaryDirectory}/program.js`;

try {
  const treeShaker = new Deno.Command(Deno.execPath(), {
    args: ['bundle', '--no-check', '--output', bundledProgramPath, 'src/index.ts'],
    cwd: programDirectory,
    stdout: 'inherit',
    stderr: 'inherit',
  });
  const treeShakerOutput = await treeShaker.output();
  if (!treeShakerOutput.success) Deno.exit(treeShakerOutput.code);

  const command = new Deno.Command(runtimeBinary, {
    args: [
      'bundle',
      '--entrypoint',
      bundledProgramPath,
      '--output',
      outputPath,
    ],
    cwd: programDirectory,
    stdout: 'inherit',
    stderr: 'inherit',
  });
  const output = await command.output();
  if (!output.success) Deno.exit(output.code);
} finally {
  await Deno.remove(temporaryDirectory, { recursive: true });
}

function argumentValue(name: string): string | undefined {
  const index = Deno.args.indexOf(name);
  if (index === -1) return undefined;
  if (index === Deno.args.length - 1) {
    throw new Error(`${name} requires a path.`);
  }
  return Deno.args[index + 1];
}
