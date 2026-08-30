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
      'Program storage',
      'Drop an ESZip bundle here',
      'Program name',
      'Program ID',
      'Uploaded',
      'Size',
      'Delete',
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
});
