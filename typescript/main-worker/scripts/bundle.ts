const defaultRuntimeBinary = Deno.build.os === 'windows'
  ? '../../mc-edge-runtime/target/debug/edge-runtime.exe'
  : '../../mc-edge-runtime/target/debug/edge-runtime';

const runtimeBinary = Deno.env.get('EDGE_RUNTIME_BIN') ?? defaultRuntimeBinary;
const programDirectory = await Deno.realPath(new URL('../', import.meta.url));
const browserUiDirectory = await Deno.realPath(new URL('../../browser-ui/', import.meta.url));
const temporaryDirectory = await Deno.makeTempDir({
  dir: programDirectory,
  prefix: '.cobbleware-eszip-',
});
const bundledProgramPath = `${temporaryDirectory}/program.js`;

try {
  const browserUiBuild = new Deno.Command(Deno.execPath(), {
    args: ['task', 'build'],
    cwd: browserUiDirectory,
    stdout: 'inherit',
    stderr: 'inherit',
  });
  const browserUiOutput = await browserUiBuild.output();
  if (!browserUiOutput.success) Deno.exit(browserUiOutput.code);

  const treeShaker = new Deno.Command(Deno.execPath(), {
    args: [
      'bundle',
      '--no-check',
      '--output',
      bundledProgramPath,
      'src/index.ts',
    ],
    cwd: programDirectory,
    stdout: 'inherit',
    stderr: 'inherit',
  });

  const treeShakerOutput = await treeShaker.output();
  if (!treeShakerOutput.success) {
    Deno.exit(treeShakerOutput.code);
  }

  const command = new Deno.Command(runtimeBinary, {
    args: [
      'bundle',
      '--entrypoint',
      bundledProgramPath,
      '--static',
      'static/**/*',
      '--output',
      'main-worker.eszip',
    ],
    cwd: programDirectory,
    stdout: 'inherit',
    stderr: 'inherit',
  });

  const output = await command.output();

  if (!output.success) {
    Deno.exit(output.code);
  }
} finally {
  await Deno.remove(temporaryDirectory, { recursive: true });
}
