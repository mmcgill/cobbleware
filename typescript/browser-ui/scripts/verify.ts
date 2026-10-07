const projectDirectory = await Deno.realPath(new URL('../', import.meta.url));
const mainWorkerDirectory = await Deno.realPath(new URL('../../main-worker/', import.meta.url));
const runtimeBinary = Deno.env.get('EDGE_RUNTIME_BIN') ??
  (Deno.build.os === 'windows'
    ? '../../mc-edge-runtime/target/debug/edge-runtime.exe'
    : '../../mc-edge-runtime/target/debug/edge-runtime');

const staticDirectory = await Deno.makeTempDir({ prefix: 'cobbleware-browser-ui-assets-' });
let assetCount = 0;
try {
  await buildUi(staticDirectory);
  await Deno.writeTextFile(`${staticDirectory}/stale-browser-ui.txt`, 'stale');
  await run(Deno.execPath(), ['task', 'bundle', '--static-dir', staticDirectory], {
    cwd: mainWorkerDirectory,
  });
  if (await fileExists(`${staticDirectory}/stale-browser-ui.txt`)) {
    throw new Error('the Main Worker bundle retained a stale Browser UI asset');
  }

  const files = await listFiles(staticDirectory);
  assetCount = files.size;
  for (const requiredPath of ['index.html', 'auth.html', 'favicon.svg']) {
    if (!files.has(requiredPath)) throw new Error(`browser bundle did not emit ${requiredPath}`);
  }

  const textAssets = await Promise.all(
    [...files]
      .filter((path) => path.endsWith('.html') || path.endsWith('.js') || path.endsWith('.css'))
      .map(async (path) => [path, await Deno.readTextFile(`${staticDirectory}/${path}`)] as const),
  );
  for (const [path, content] of textAssets) {
    if (content.includes('UNUSED_BROWSER_UI_TREE_SHAKING_SENTINEL')) {
      throw new Error(`unused ESM export was retained in ${path}`);
    }
    if (/https?:\/\//i.test(content)) {
      throw new Error(`generated asset ${path} contains an external HTTP(S) reference`);
    }
  }

  const entries = new Map(textAssets.filter(([path]) => path.endsWith('.html')));
  const authReferences = staticReferences(entries.get('auth.html') ?? '', 'auth.html', files);
  staticReferences(entries.get('index.html') ?? '', 'index.html', files);

  const gameDirectory = await Deno.makeTempDir({ prefix: 'cobbleware-browser-ui-' });
  try {
    const listener = Deno.listen({ hostname: '127.0.0.1', port: 0 });
    const port = (listener.addr as Deno.NetAddr).port;
    listener.close();

    const server = new Deno.Command(runtimeBinary, {
      args: [
        'start',
        '--ip',
        '127.0.0.1',
        '--port',
        String(port),
        '--main-service',
        'main-worker.eszip',
        '--main-entrypoint',
        'file:///src/index.ts',
      ],
      cwd: mainWorkerDirectory,
      env: {
        COBBLEWARE_API_KEY: 'browser-ui-verification-key',
        COBBLEWARE_GAME_DIR: gameDirectory,
      },
      stdout: 'inherit',
      stderr: 'inherit',
    }).spawn();

    try {
      await waitForServer(port);
      const index = await fetch(`http://127.0.0.1:${port}/index.html`, { redirect: 'manual' });
      if (index.status !== 302 || index.headers.get('location') !== '/auth.html') {
        throw new Error('the Main Worker did not protect the generated dashboard');
      }

      for (const path of ['auth.html', ...authReferences]) {
        const response = await fetch(`http://127.0.0.1:${port}/${path}`);
        if (!response.ok) throw new Error(`ESZip did not serve ${path}: HTTP ${response.status}`);
        if (
          path.endsWith('.css') &&
          response.headers.get('content-type') !== 'text/css; charset=utf-8'
        ) {
          throw new Error(`ESZip served ${path} with the wrong content type`);
        }
      }
    } finally {
      server.kill('SIGTERM');
      await server.status;
    }
  } finally {
    await Deno.remove(gameDirectory, { recursive: true });
  }
} finally {
  await Deno.remove(staticDirectory, { recursive: true });
}

console.log(`Verified ${assetCount} local Browser UI assets through the Main Worker ESZip.`);

function buildUi(staticDirectory: string): Promise<void> {
  return run(Deno.execPath(), ['task', 'build', '--static-dir', staticDirectory], {
    cwd: projectDirectory,
  });
}

async function run(
  command: string,
  args: string[],
  options: Deno.CommandOptions,
): Promise<void> {
  const output = await new Deno.Command(command, {
    ...options,
    args,
    stdout: 'inherit',
    stderr: 'inherit',
  }).output();
  if (!output.success) throw new Error(`${command} exited with code ${output.code}`);
}

async function fileExists(path: string): Promise<boolean> {
  try {
    await Deno.stat(path);
    return true;
  } catch (error) {
    if (error instanceof Deno.errors.NotFound) return false;
    throw error;
  }
}

async function listFiles(directory: string): Promise<Set<string>> {
  const paths = new Set<string>();
  for await (const entry of Deno.readDir(directory)) {
    if (entry.isDirectory) {
      for (const nested of await listFiles(`${directory}/${entry.name}`)) {
        paths.add(`${entry.name}/${nested}`);
      }
    } else if (entry.isFile) {
      paths.add(entry.name);
    }
  }
  return paths;
}

function staticReferences(html: string, from: string, files: Set<string>): Set<string> {
  if (html.length === 0) throw new Error(`browser bundle did not emit ${from}`);
  const references = new Set<string>();
  for (const reference of html.matchAll(/(?:href|src)="([^"]+)"/g)) {
    const target = reference[1]!;
    if (target.startsWith('#') || target.startsWith('data:')) continue;
    if (target.startsWith('/') || target.includes('://')) {
      throw new Error(`${from} contains a non-local reference: ${target}`);
    }
    const localPath = target.replace(/^\.\//, '');
    if (!files.has(localPath)) {
      throw new Error(`${from} references missing generated asset ${target}`);
    }
    references.add(localPath);
  }
  return references;
}

async function waitForServer(port: number): Promise<void> {
  let lastError: unknown;
  for (let attempt = 0; attempt < 40; attempt += 1) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/auth.html`);
      if (response.ok) return;
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error(`Main Worker did not start: ${String(lastError)}`);
}
