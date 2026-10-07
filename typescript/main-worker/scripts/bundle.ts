import { isAbsolute, resolve } from 'node:path';

const defaultRuntimeBinary = Deno.build.os === 'windows'
  ? '../../mc-edge-runtime/target/debug/edge-runtime.exe'
  : '../../mc-edge-runtime/target/debug/edge-runtime';

const runtimeBinary = Deno.env.get('EDGE_RUNTIME_BIN') ?? defaultRuntimeBinary;
const programDirectory = await Deno.realPath(new URL('../', import.meta.url));
const runtimeBinaryPath = isAbsolute(runtimeBinary)
  ? runtimeBinary
  : resolve(programDirectory, runtimeBinary);
const browserUiDirectory = await Deno.realPath(new URL('../../browser-ui/', import.meta.url));
const staticDirectory = argumentValue('--static-dir') ?? `${programDirectory}/static`;
const requestedOutputPath = argumentValue('--output') ?? 'main-worker.eszip';
const outputPath = isAbsolute(requestedOutputPath)
  ? requestedOutputPath
  : resolve(programDirectory, requestedOutputPath);
const temporaryDirectory = await Deno.makeTempDir({
  dir: programDirectory,
  prefix: '.cobbleware-eszip-',
});
const bundledSourceDirectory = `${temporaryDirectory}/src`;
const bundledProgramPath = `${bundledSourceDirectory}/index.ts`;
const bundledStaticDirectory = `${temporaryDirectory}/static`;

try {
  const browserUiBuild = new Deno.Command(Deno.execPath(), {
    args: ['task', 'build', '--static-dir', staticDirectory],
    cwd: browserUiDirectory,
    stdout: 'inherit',
    stderr: 'inherit',
  });
  const browserUiOutput = await browserUiBuild.output();
  if (!browserUiOutput.success) Deno.exit(browserUiOutput.code);

  // The runtime maps static assets relative to the ESZip entrypoint. Copy both
  // the entrypoint and generated assets into one small bundle tree so the
  // static URLs are always file:///static/... regardless of --static-dir.
  await Deno.mkdir(bundledSourceDirectory);
  await copyDirectory(staticDirectory, bundledStaticDirectory);

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

  const command = new Deno.Command(runtimeBinaryPath, {
    args: [
      'bundle',
      '--entrypoint',
      'src/index.ts',
      '--static',
      'static/**/*',
      '--output',
      outputPath,
    ],
    cwd: temporaryDirectory,
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

function argumentValue(name: string): string | undefined {
  const index = Deno.args.indexOf(name);
  if (index === -1) return undefined;
  if (index === Deno.args.length - 1) {
    throw new Error(`${name} requires a path.`);
  }
  return Deno.args[index + 1];
}

async function copyDirectory(source: string, destination: string): Promise<void> {
  await Deno.mkdir(destination, { recursive: true });
  for await (const entry of Deno.readDir(source)) {
    const sourcePath = `${source}/${entry.name}`;
    const destinationPath = `${destination}/${entry.name}`;
    if (entry.isDirectory) {
      await copyDirectory(sourcePath, destinationPath);
    } else if (entry.isFile) {
      await Deno.copyFile(sourcePath, destinationPath);
    } else {
      throw new Error(`Cannot bundle unsupported static asset type: ${sourcePath}`);
    }
  }
}
