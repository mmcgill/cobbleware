const projectDirectory = await Deno.realPath(new URL('../', import.meta.url));
const outputDirectory = await Deno.realPath(new URL('../../main-worker/', import.meta.url));
const staticDirectory = `${outputDirectory}/static`;

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
