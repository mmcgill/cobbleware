const HELLO_BODY = JSON.stringify({ message: 'hello' });

const JSON_HEADERS = {
  'content-type': 'application/json',
};

export function handleRequest(request: Request): Response {
  const { pathname } = new URL(request.url);

  if (pathname === '/hello') {
    return new Response(HELLO_BODY, {
      status: 200,
      headers: JSON_HEADERS,
    });
  }

  return new Response(null, { status: 404 });
}

if (import.meta.main) {
  Deno.serve(handleRequest);
}
