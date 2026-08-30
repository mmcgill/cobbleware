# Browser UI for Players

The Browser UI is an HTML/JS app served by the Main Worker that allows Players to view and manage their Programs on a server.
The Browser UI relies on the Player API.

Requests to the root are redirected to `/index.html`.

Unauthenticated requests (that is, requests without the expected session cookie) are redirected
to `/auth.html`.

## Index page

The `/index.html` page presents the user with a table that lists their programs. The table
contains columns for program id, name, upload date, size, and an action to delete each program.

The page also shows a program quota usage bar showing the total used and remaining program space
available to the player.

The page provides a drag-and-drop upload area for ESZip bundles and a file-picker fallback. Dropping or choosing
one file starts a `POST /playerapi/v1/programs` upload using `multipart/form-data`. The uploaded filename is used
as the program name when the optional name field is blank; otherwise the supplied name is sent to the server.
On success, the program list and quota usage are refreshed without a full page reload. Upload errors are shown
without removing existing programs from the table.

Each program has a delete action. The UI asks for confirmation before sending
`DELETE /playerapi/v1/programs/{id}`. After a successful deletion, the program list and quota usage are refreshed.
The UI displays server/API errors and preserves the current list when an upload or deletion fails. An expired
session redirects the player to `/auth.html`.

## Authentication Page

The `/auth.html` page serves a page that:

* randomly generates an 8-character uppercase alphanumeric auth code
* presents the player with a slash command embedding that code to be run on the Minecraft server
* polls the `/playerapi/v1/authenticate` endpoint with the auth code
* on success, redirects to `/index.html`
