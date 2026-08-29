const defaultRuntimeBinary = Deno.build.os === 'windows'
  ? '../../mc-edge-runtime/target/debug/edge-runtime.exe'
  : '../../mc-edge-runtime/target/debug/edge-runtime';

const runtimeBinary = Deno.env.get('EDGE_RUNTIME_BIN') ?? defaultRuntimeBinary;

const command = new Deno.Command(runtimeBinary, {
  args: [
    'start',
    '--main-service',
    'main-worker.eszip',
    '--main-entrypoint',
    'file:///src/index.ts',
  ],
  stdin: 'inherit',
  stdout: 'inherit',
  stderr: 'inherit',
});

const child = command.spawn();
const status = await child.status;

Deno.exit(status.code);
