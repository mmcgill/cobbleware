import { handleRequest } from '../src/index.ts';

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
