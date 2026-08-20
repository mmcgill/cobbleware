const archive = await Deno.readFile('hello-world.eszip');
const port = 8000;

const server = Deno.serve({ hostname: '127.0.0.1', port }, (request) => {
  const { pathname } = new URL(request.url);

  if (pathname !== '/' && pathname !== '/hello-world.eszip') {
    return new Response(null, { status: 404 });
  }

  return new Response(archive, {
    headers: {
      'content-length': String(archive.byteLength),
      'content-type': 'application/octet-stream',
    },
  });
});

console.log(`Serving hello-world.eszip at http://127.0.0.1:${server.addr.port}/hello-world.eszip`);
