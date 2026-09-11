const archive = await Deno.readFile('examples.eszip');
const port = 8000;

const server = Deno.serve({ hostname: '127.0.0.1', port }, (request) => {
  const { pathname } = new URL(request.url);

  if (pathname !== '/' && pathname !== '/examples.eszip') {
    return new Response(null, { status: 404 });
  }

  return new Response(archive, {
    headers: {
      'content-length': String(archive.byteLength),
      'content-type': 'application/octet-stream',
    },
  });
});

console.log(`Serving examples.eszip at http://127.0.0.1:${server.addr.port}/examples.eszip`);
