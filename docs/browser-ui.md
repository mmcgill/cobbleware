# Browser UI for Players

The Browser UI is an HTML/JS app served by the Main Worker that allows Players to view and replace their active
Program on a server.
The Browser UI relies on the Player API.

Requests to the root are redirected to `/index.html`.

Unauthenticated requests (that is, requests without the expected session cookie) are redirected
to `/auth.html`.

## Index page

The `/index.html` page presents the logged-in player's Minecraft profile name and a logout control, along with one
active-program section. It loads the profile name from `/playerapi/v1/whoami`, program metadata from
`/playerapi/v1/programs`, and the active program's start-state names from
`/playerapi/v1/programs/{id}/startStates`. The section shows the program id, name, upload date, size, and start
states. It has no delete action.

The active-program section is also the drag-and-drop target for ESZip bundles and provides a file-picker fallback.
Dropping or choosing one file immediately starts a `POST /playerapi/v1/programs` upload using `multipart/form-data`;
there is no separate upload confirmation control. The UI does not offer a program-name field, so it always uses the
uploaded filename. On success, the active-program metadata and start states are refreshed without a full page reload.
Upload errors are shown and the currently displayed active program is preserved.

The UI does not provide a way to delete the active program; uploading a replacement is the only program-management
operation.

The page also has an execution-stats section. It opens one WebSocket to
`/playerapi/v1/execution-stats` and renders the latest player-scoped `ExecutionStats` protobuf message for the
logged-in Player: instrumentation-window coverage, offered events per execution sync, accepted/dropped events, effect
attempts, failures and timing, and currently loaded running/stopped Cobbleputers. It displays when the sample was
captured and marks the view stale when no update arrives for more than a few reporting intervals. The section initially
indicates that no statistics are available if the Main Worker has not yet received a report for the Player. A rejected
or closed connection is shown as a stats-connection error and may be retried without reloading the rest of the page.
When the server reports that an execution-stats stream is already open in another browser window, the UI shows that
specific error and does not retry the connection. The UI never displays another Player's statistics.

Activating the logout control sends `POST /playerapi/v1/logout`. After a successful logout, the page redirects to
`/auth.html`. A failed logout leaves the page and its current program list visible and shows the server or network
error so the player can try again. An expired session redirects the player to `/auth.html`.

## Authentication Page

The `/auth.html` page serves a page that:

* randomly generates an 8-character uppercase alphanumeric auth code
* presents the player with a slash command embedding that code to be run on the Minecraft server
* polls the `/playerapi/v1/authenticate` endpoint with the auth code
* on success, redirects to `/index.html`
