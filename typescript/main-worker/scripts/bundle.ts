const defaultRuntimeBinary = Deno.build.os === 'windows'
  ? '../../mc-edge-runtime/target/debug/edge-runtime.exe'
  : '../../mc-edge-runtime/target/debug/edge-runtime';

const runtimeBinary = Deno.env.get('EDGE_RUNTIME_BIN') ?? defaultRuntimeBinary;

const command = new Deno.Command(runtimeBinary, {
  args: [
    'bundle',
    '--entrypoint',
    'src/index.ts',
    '--static',
    'static/**/*',
    '--output',
    'main-worker.eszip',
  ],
  stdout: 'inherit',
  stderr: 'inherit',
});

const output = await command.output();

if (!output.success) {
  Deno.exit(output.code);
}
