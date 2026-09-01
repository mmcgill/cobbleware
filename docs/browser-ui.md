# Browser UI for Players

The Browser UI is an HTML/JS app served by the Main Worker that allows Players to view and replace their active
Program on a server.
The Browser UI relies on the Player API.

Requests to the root are redirected to `/index.html`.

Unauthenticated requests (that is, requests without the expected session cookie) are redirected
to `/auth.html`.

## Index page

The `/index.html` page presents the logged-in player's Minecraft profile name and a logout control, along with a
table that lists their active program, if one exists. The page loads the profile name from `/playerapi/v1/whoami` and
loads the program list from `/playerapi/v1/programs`. The table contains columns for program id, name, upload date,
and size. It has at most one row and has no delete action.

The page also shows a program quota usage bar showing the active program's size and the remaining program space
available to the player.

The page provides a drag-and-drop upload area for ESZip bundles and a file-picker fallback. Dropping or choosing
one file starts a `POST /playerapi/v1/programs` upload using `multipart/form-data`. The uploaded filename is used
as the program name when the optional name field is blank; otherwise the supplied name is sent to the server.
On success, the program list and quota usage are refreshed without a full page reload. If an active program already
exists, the refreshed row represents the replacement. Upload errors are shown and the currently displayed active
program is preserved.

The UI does not provide a way to delete the active program; uploading a replacement is the only program-management
operation.

Activating the logout control sends `POST /playerapi/v1/logout`. After a successful logout, the page redirects to
`/auth.html`. A failed logout leaves the page and its current program list visible and shows the server or network
error so the player can try again. An expired session redirects the player to `/auth.html`.

## Authentication Page

The `/auth.html` page serves a page that:

* randomly generates an 8-character uppercase alphanumeric auth code
* presents the player with a slash command embedding that code to be run on the Minecraft server
* polls the `/playerapi/v1/authenticate` endpoint with the auth code
* on success, redirects to `/index.html`
