const defaultRuntimeBinary = Deno.build.os === 'windows'
  ? 'mc-edge-runtime/target/debug/edge-runtime.exe'
  : 'mc-edge-runtime/target/debug/edge-runtime';

const runtimeBinary = Deno.env.get('EDGE_RUNTIME_BIN') ?? defaultRuntimeBinary;
const developmentServerWorldDir = 'java/cobbleware/run/world';

const command = new Deno.Command(runtimeBinary, {
  args: [
    'start',
    '--main-service',
    'typescript/main-worker/main-worker.eszip',
    '--main-entrypoint',
    'file:///src/index.ts',
    '--event-worker',
    'typescript/event-worker/event-worker.eszip',
    '--events-entrypoint',
    'file:///src/index.ts',
  ],
  stdin: 'inherit',
  stdout: 'inherit',
  stderr: 'inherit',
  env: {
    ...Deno.env.toObject(),
    COBBLEWARE_GAME_DIR: developmentServerWorldDir,
  },
  cwd: '../..',
});

const child = command.spawn();
const status = await child.status;

Deno.exit(status.code);
