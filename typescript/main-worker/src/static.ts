const STATIC_ROOT = new URL('../static/', import.meta.url);

const CONTENT_TYPES: Record<string, string> = {
  '.css': 'text/css; charset=utf-8',
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
};

function assetForPath(pathname: string): URL | undefined {
  let decodedPathname: string;
  try {
    decodedPathname = decodeURIComponent(pathname);
  } catch {
    return undefined;
  }

  if (
    !decodedPathname.startsWith('/') ||
    decodedPathname === '/' ||
    decodedPathname.endsWith('/') ||
    decodedPathname.startsWith('//') ||
    decodedPathname.includes('\0') ||
    decodedPathname.includes('\\') ||
    decodedPathname.split('/').includes('..')
  ) {
    return undefined;
  }

  const asset = new URL(`.${decodedPathname}`, STATIC_ROOT);
  return asset.href.startsWith(STATIC_ROOT.href) ? asset : undefined;
}

function contentTypeForPath(pathname: string): string {
  const extension = pathname.slice(pathname.lastIndexOf('.')).toLowerCase();
  return CONTENT_TYPES[extension] ?? 'application/octet-stream';
}

export async function serveStatic(request: Request): Promise<Response> {
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    return new Response(null, { status: 405, headers: { allow: 'GET, HEAD' } });
  }

  const { pathname } = new URL(request.url);
  const asset = assetForPath(pathname);
  if (asset === undefined) {
    return new Response(null, { status: 404 });
  }

  let content: Uint8Array;
  try {
    content = new Uint8Array(await Deno.readFile(asset));
  } catch (error) {
    if (error instanceof Deno.errors.NotFound) {
      return new Response(null, { status: 404 });
    }
    throw error;
  }

  return new Response(request.method === 'HEAD' ? null : content.buffer as ArrayBuffer, {
    status: 200,
    headers: {
      'cache-control': pathname === '/auth.html' ? 'no-store' : 'no-cache',
      'content-type': contentTypeForPath(pathname),
    },
  });
}
