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

Deno.test('/index.html contains the programs UI', async () => {
  const response = await serveStatic(new Request('http://localhost/index.html'));

  if (response.status !== 200) {
    throw new Error(`expected status 200, got ${response.status}`);
  }

  const body = await response.text();
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
      '/playerapi/v1/whoami',
      '/playerapi/v1/logout',
    ]
  ) {
    if (!body.includes(landmark)) {
      throw new Error(`expected index page to contain ${landmark}`);
    }
  }
  if (body.includes('program management UI is not implemented yet')) {
    throw new Error('index page still contains the placeholder UI');
  }
  for (
    const removedControl of [
      'Program storage',
      'Program name',
      'id="drop-zone"',
      'id="upload-button"',
    ]
  ) {
    if (body.includes(removedControl)) {
      throw new Error(`index page still contains removed UI: ${removedControl}`);
    }
  }
});

Deno.test('static assets are found by path and use their file type', async () => {
  const response = await serveStatic(new Request('http://localhost/style.css'));

  if (response.status !== 200) {
    throw new Error(`expected status 200, got ${response.status}`);
  }
  if (response.headers.get('content-type') !== 'text/css; charset=utf-8') {
    throw new Error('expected a CSS response');
  }
  if (!(await response.text()).includes('--surface')) {
    throw new Error('expected the shared stylesheet to contain the UI theme');
  }
});

Deno.test('public static assets can be loaded without a Player session', async () => {
  const response = await handleRequest(new Request('http://localhost/style.css'));

  if (response.status !== 200) {
    throw new Error(`expected status 200, got ${response.status}`);
  }
});

Deno.test('/auth.html uses the shared stylesheet', async () => {
  const response = await serveStatic(new Request('http://localhost/auth.html'));
  const body = await response.text();

  if (response.status !== 200) {
    throw new Error(`expected status 200, got ${response.status}`);
  }
  for (const landmark of ['href="./style.css"', 'class="card auth-card"', 'class="command"']) {
    if (!body.includes(landmark)) {
      throw new Error(`expected auth page to contain ${landmark}`);
    }
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
