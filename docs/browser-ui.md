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
`/playerapi/v1/execution-stats` and receives player-scoped `ExecutionStatsTick` protobuf messages. It retains and
aggregates up to 600 received ticks locally to render coverage, offered events per execution sync, accepted/dropped
events, and effect attempts, failures and timing. It also displays the
newest tick's current User Worker memory usage as a progress bar, using `used_bytes` out of `max_bytes`; memory is
instantaneous and is never aggregated over the retained window. A player without a live User Worker displays zero
usage out of the configured limit. It displays when the newest sample was captured, marks the view stale when no
update arrives for more than a few reporting intervals, and marks coverage incomplete when tick-sequence gaps are
observed. The section initially indicates that no statistics are available if the Main Worker has not yet received a
report for the Player. A rejected or closed connection is shown as a stats-connection error and may be retried without
reloading the rest of the page.

The section also displays one responsive 200-pixel-high canvas history. Each received tick occupies one right-aligned
CSS-pixel column. The middle of the canvas divides the two metric families: event columns extend upward and effect
columns extend downward. Event-column height is the sum of accepted and dropped events, colored by outcome.
Effect-column height is the number of attempted effects, split into successful and failed applications, since failed
effects are a subset of attempts. Each half is capped at 100 pixels; a distinct cap marker identifies a tick whose
count exceeds the visible range. The graph retains only received ticks, so a reported sequence gap remains a coverage
warning rather than being displayed as a zero-valued tick. Hovering the canvas shows the event or effect scale value
at the pointer, selecting events above the divider and effects below it.

When the server reports that an execution-stats stream is already open in another browser window, the UI shows that
specific error and does not retry the connection. The UI never displays another Player's statistics.

Activating the logout control sends `POST /playerapi/v1/logout`. After a successful logout, the page redirects to
`/auth.html`. A failed logout leaves the page and its current program list visible and shows the server or network
error so the player can try again. An expired session redirects the player to `/auth.html`.

For ease of troubleshooting, Execution statistics and Logs are alternative tabs in one execution-observability
section. Logs displays a streaming, non-durable feed of events for the Player's program executions while retaining
up to 10k received events locally, including while the Execution statistics tab is selected. The scrollable feed
auto-scrolls by default, but the Player can toggle that behavior. It is filterable by program-log level
(Debug, Info, Warn, Error) and event type (program log, exception, CPU/memory limit exceeded, execution lifecycle
transition, or execution-pool transition). Pool entries show whether the Player entered the guaranteed or active
best-effort pool, is waiting for a best-effort slot, or is no longer eligible for a pool. The logs stream
has independent connection status and retry handling; the documented already-open-in-another-window error is shown
without retrying automatically. Each feed row records the client-side time at which the Browser received that event.

Limits is a third tab in that section. On page load it reads `/playerapi/v1/limits`, then shows the current loaded,
running execution count and total durable execution-state bytes against their enforced per-player caps and the snapshot
update time. While the page is open, each visible execution-transition log entry carries the running-execution and
state-byte caps, while execution-statistics ticks refresh the state-byte usage; the UI does not poll. Limits has
independent unavailable/error and retry handling. A snapshot unavailable after a Main Worker restart or a temporary
Mod connection failure is shown as unavailable, not as zero usage.

## Authentication Page

The `/auth.html` page serves a page that:

* randomly generates an 8-character uppercase alphanumeric auth code
* presents the player with a slash command embedding that code to be run on the Minecraft server
* polls the `/playerapi/v1/authenticate` endpoint with the auth code
* on success, redirects to `/index.html`
