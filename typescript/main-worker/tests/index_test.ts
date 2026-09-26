import { handleRequest } from '../src/index.ts';
import { serveStatic } from '../src/static.ts';

Deno.test('/hello returns the static JSON response', async () => {
  const response = await handleRequest(new Request('http://localhost/hello'));

  if (response.status !== 200) {
    throw new Error(`expected status 200, got ${response.status}`);
  }

  if (response.headers.get('content-type') !== 'application/json') {
    throw new Error('expected an application/json response');
  }

  const body = await response.json();
  if (JSON.stringify(body) !== JSON.stringify({ message: 'hello' })) {
    throw new Error(`unexpected response body: ${JSON.stringify(body)}`);
  }
});

Deno.test('unknown API paths return 401 without a Player session', async () => {
  const response = await handleRequest(new Request('http://localhost/playerapi/v1/unknown'));

  if (response.status !== 401) {
    throw new Error(`expected status 401, got ${response.status}`);
  }
});

Deno.test('/index.html contains the generated programs UI', async () => {
  const html = await staticText('/index.html');
  for (
    const landmark of [
      'Your active program',
      'Start states',
      'Drag an ESZip bundle anywhere in this section',
      'Choose a file',
      'Program ID',
      'Uploaded',
      'Size',
      'player-profile-name',
      'Log out',
      'Events and effects per tick',
      'stats-activity-graph',
      'stats-graph-tooltip',
      'stats-memory-progress',
      'Limits',
      'limits-running-executions-progress',
    ]
  ) {
    if (!html.includes(landmark)) {
      throw new Error(`expected index page to contain ${landmark}`);
    }
  }

  for (const removedControl of ['Program storage', 'Program name', 'id="drop-zone"']) {
    if (html.includes(removedControl)) {
      throw new Error(`index page still contains removed UI: ${removedControl}`);
    }
  }

  const scripts = references(html).filter((path) => path.endsWith('.js'));
  if (scripts.length === 0) {
    throw new Error('expected the generated dashboard to have a module script');
  }
  const scriptBody = (await Promise.all(scripts.map((path) => staticText(`/${path}`)))).join('\n');
  for (
    const behavior of [
      '/playerapi/v1/whoami',
      '/playerapi/v1/logout',
      'execution-stats-stream-already-open',
      '/playerapi/v1/execution-stats',
      '/playerapi/v1/limits',
    ]
  ) {
    if (!scriptBody.includes(behavior)) {
      throw new Error(`expected generated dashboard JavaScript to contain ${behavior}`);
    }
  }
});

Deno.test('generated styles are served with the CSS content type', async () => {
  const stylePath = stylesheetReference(await staticText('/index.html'));
  const response = await serveStatic(new Request(`http://localhost/${stylePath}`));

  if (response.status !== 200) {
    throw new Error(`expected status 200, got ${response.status}`);
  }
  if (response.headers.get('content-type') !== 'text/css; charset=utf-8') {
    throw new Error('expected a CSS response');
  }
  if (!(await response.text()).includes('--surface')) {
    throw new Error('expected the generated stylesheet to contain the UI theme');
  }
});

Deno.test('generated public static assets can be loaded without a Player session', async () => {
  const stylePath = stylesheetReference(await staticText('/index.html'));
  const response = await handleRequest(new Request(`http://localhost/${stylePath}`));

  if (response.status !== 200) {
    throw new Error(`expected status 200, got ${response.status}`);
  }
});

Deno.test('/auth.html has generated assets', async () => {
  const html = await staticText('/auth.html');
  for (const landmark of ['class="card auth-card"', 'class="command"']) {
    if (!html.includes(landmark)) {
      throw new Error(`expected auth page to contain ${landmark}`);
    }
  }

  const assets = references(html);
  if (!assets.some((path) => path.endsWith('.css'))) {
    throw new Error('expected auth page to reference generated CSS');
  }
  if (!assets.some((path) => path.endsWith('.js'))) {
    throw new Error('expected auth page to reference generated JavaScript');
  }
});

Deno.test('missing static assets return 404', async () => {
  const response = await serveStatic(new Request('http://localhost/missing.css'));

  if (response.status !== 404) {
    throw new Error(`expected status 404, got ${response.status}`);
  }
});

Deno.test('static path traversal is rejected', async () => {
  const response = await serveStatic(new Request('http://localhost/%2e%2e/src/index.ts'));

  if (response.status !== 404) {
    throw new Error(`expected status 404, got ${response.status}`);
  }
});

async function staticText(path: string): Promise<string> {
  const response = await serveStatic(new Request(`http://localhost${path}`));
  if (response.status !== 200) {
    throw new Error(`expected ${path} to be served, got ${response.status}`);
  }
  return response.text();
}

function references(html: string): string[] {
  return [...html.matchAll(/(?:href|src)="([^"]+)"/g)].map((match) => match[1]!);
}

function stylesheetReference(html: string): string {
  const path = references(html).find((reference) => reference.endsWith('.css'));
  if (path === undefined) throw new Error('expected an HTML stylesheet reference');
  return path;
}
