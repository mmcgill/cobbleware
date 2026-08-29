import { handleRequest } from '../src/index.ts';

Deno.test('/hello returns the static JSON response', async () => {
  const response = handleRequest(new Request('http://localhost/hello'));

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

Deno.test('unknown paths return 404', () => {
  const response = handleRequest(new Request('http://localhost/unknown'));

  if (response.status !== 404) {
    throw new Error(`expected status 404, got ${response.status}`);
  }
});
