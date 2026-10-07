const projectDirectory = await Deno.realPath(new URL('../', import.meta.url));
const staticDirectory = argumentValue('--static-dir') ??
  `${await Deno.realPath(new URL('../../main-worker/', import.meta.url))}/static`;

await Deno.remove(staticDirectory, { recursive: true }).catch((error: unknown) => {
  if (!(error instanceof Deno.errors.NotFound)) throw error;
});
await Deno.mkdir(staticDirectory, { recursive: true });
await Deno.copyFile(`${projectDirectory}/src/favicon.svg`, `${staticDirectory}/favicon.svg`);

const bundle = new Deno.Command(Deno.execPath(), {
  args: [
    'bundle',
    '--platform=browser',
    '--minify',
    '--outdir',
    staticDirectory,
    'src/index.html',
    'src/auth.html',
  ],
  cwd: projectDirectory,
  stdout: 'inherit',
  stderr: 'inherit',
});

const output = await bundle.output();
if (!output.success) Deno.exit(output.code);

function argumentValue(name: string): string | undefined {
  const index = Deno.args.indexOf(name);
  if (index === -1) return undefined;
  if (index === Deno.args.length - 1) {
    throw new Error(`${name} requires a path.`);
  }
  return Deno.args[index + 1];
}
