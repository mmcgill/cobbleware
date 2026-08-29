const STATIC_ASSETS = new Map<string, URL>([
  ['/auth.html', new URL('../static/auth.html', import.meta.url)],
  ['/index.html', new URL('../static/index.html', import.meta.url)],
]);

export async function serveStatic(request: Request): Promise<Response> {
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    return new Response(null, { status: 405, headers: { allow: 'GET, HEAD' } });
  }

  const { pathname } = new URL(request.url);
  const asset = STATIC_ASSETS.get(pathname);
  if (asset === undefined) {
    return new Response(null, { status: 404 });
  }

  const content = await Deno.readFile(asset);
  return new Response(request.method === 'HEAD' ? null : content, {
    status: 200,
    headers: {
      'cache-control': pathname === '/auth.html' ? 'no-store' : 'no-cache',
      'content-type': 'text/html; charset=utf-8',
    },
  });
}
