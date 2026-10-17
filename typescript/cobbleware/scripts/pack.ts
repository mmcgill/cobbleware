const packageDirectory = new URL('../', import.meta.url);
const packageDirectoryPath = filesystemPath(packageDirectory);
const { allowDirty, versionOverride } = parsePackArguments(Deno.args);
const config = await readPackageConfig();
const packageVersion = versionOverride ?? config.version;
const tarballPath = joinPath(
  packageDirectoryPath,
  `${config.name.replace(/^@/, '').replaceAll('/', '-')}-${packageVersion}.tgz`,
);
const temporaryDirectory = await Deno.makeTempDir({
  dir: packageDirectoryPath,
  prefix: '.pack-',
});

try {
  const packedTarballPath = joinPath(temporaryDirectory, 'packed.tgz');
  const unpackedDirectory = joinPath(temporaryDirectory, 'unpacked');
  const repackedTarballPath = joinPath(temporaryDirectory, 'repacked.tgz');

  await run(Deno.execPath(), [
    'pack',
    '--ignore=tests,scripts',
    '--no-source-maps',
    '--output',
    packedTarballPath,
    ...(versionOverride === undefined ? [] : ['--set-version', versionOverride]),
    ...(allowDirty ? ['--allow-dirty'] : []),
  ]);

  await Deno.mkdir(unpackedDirectory);
  await run('tar', ['-xzf', packedTarballPath, '-C', unpackedDirectory]);

  const manifestPath = joinPath(unpackedDirectory, 'package', 'package.json');
  const manifest = JSON.parse(await Deno.readTextFile(manifestPath)) as Record<string, unknown>;
  manifest.repository = {
    type: 'git',
    url: 'git+https://github.com/mmcgill/cobbleware.git',
    directory: 'typescript/cobbleware',
  };
  manifest.homepage = 'https://github.com/mmcgill/cobbleware/tree/main/typescript/cobbleware';
  await Deno.writeTextFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);

  await run('tar', ['-czf', repackedTarballPath, '-C', unpackedDirectory, 'package']);
  await replaceFile(repackedTarballPath, tarballPath);
  console.log(`Packed ${config.name}@${packageVersion} with repository metadata: ${tarballPath}`);
} finally {
  await Deno.remove(temporaryDirectory, { recursive: true }).catch((error: unknown) => {
    if (error instanceof Deno.errors.NotFound) return;
    throw error;
  });
}

function parsePackArguments(args: string[]): { allowDirty: boolean; versionOverride?: string } {
  let allowDirty = false;
  let versionOverride: string | undefined;

  for (let index = 0; index < args.length; index++) {
    const argument = args[index];
    if (argument === '--allow-dirty' && !allowDirty) {
      allowDirty = true;
      continue;
    }
    if (argument === '--version' && versionOverride === undefined) {
      versionOverride = args[++index];
      if (!isSafeVersion(versionOverride)) {
        throw new Error('The --version value must be a valid, safe SemVer version.');
      }
      continue;
    }
    throw new Error('Usage: deno task pack [--allow-dirty] [--version <version>]');
  }

  return { allowDirty, versionOverride };
}

async function readPackageConfig(): Promise<{ name: string; version: string }> {
  const configPath = joinPath(packageDirectoryPath, 'deno.json');
  const config = JSON.parse(await Deno.readTextFile(configPath)) as Record<string, unknown>;

  if (typeof config.name !== 'string' || typeof config.version !== 'string') {
    throw new Error('deno.json must define string name and version fields');
  }

  return { name: config.name, version: config.version };
}

async function run(command: string, args: string[]): Promise<void> {
  const result = await new Deno.Command(command, {
    args,
    cwd: packageDirectoryPath,
    stdout: 'inherit',
    stderr: 'inherit',
  }).output();

  if (!result.success) {
    throw new Error(`${command} exited with status ${result.code}`);
  }
}

async function replaceFile(source: string, destination: string): Promise<void> {
  await Deno.remove(destination).catch((error: unknown) => {
    if (error instanceof Deno.errors.NotFound) return;
    throw error;
  });
  await Deno.rename(source, destination);
}

function joinPath(...parts: string[]): string {
  const separator = Deno.build.os === 'windows' ? '\\' : '/';
  return parts.map((part, index) => {
    if (index === 0) return part.replace(/[\\/]+$/, '');
    return part.replace(/^[\\/]+|[\\/]+$/g, '');
  }).join(separator);
}

function filesystemPath(url: URL): string {
  const path = decodeURIComponent(url.pathname);
  return Deno.build.os === 'windows' ? path.replace(/^\/([A-Za-z]:)/, '$1') : path;
}

function isSafeVersion(version: string | undefined): version is string {
  return version !== undefined &&
    /^(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/
      .test(version);
}
