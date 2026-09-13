# Use Cases

## Authentication

### Logging out

**AU01**: As a Player, I want to log out of the Browser UI so that my current browser session is no longer usable.

After logging out, the Player must authenticate again before accessing the Browser UI or their programs.

## Programs

Each player has at most one active program at a time.

### Viewing

**PG01**: As a Player, I want to view the metadata for my active program stored on a Server and its size, so that I can
tell how close to the size limit I am.

A Program is an ESZip bundle and associated metadata. Programs belong to players.
A program has the following metadata:

* program id (uniquely identifies the given program on the server that contains it)
* program name (chosen by the user, or derived from the uploaded filename)
* upload date
* size in bytes

A player can view only their own program. A player cannot see other players' programs.

### Uploading

**PG02**: As a Player, I want to upload a new program from my computer so that I can run it on Cobbleputers.

The player provides the ESZip bundle for the program, normally by dragging and dropping an ESZip file into
the Browser UI. The player may optionally provide a program name; if they do not, the server uses the uploaded
file's basename, retaining its extension. The server removes directory components from the filename before
using it as a name.

The server auto-assigns a program id upon successful upload. The uploaded bytes are treated as opaque at upload time;
validation is deferred until the program is loaded for execution. The server makes the new program active by updating
the player's `programs.json` manifest, then deletes the previous program (if there was one). The manifest is the source
of truth for the active program, and a later upload cleans up program files not referenced by the manifest after a
failed replacement. The uploaded payload must fit within the player's program size quota.

## Execution Statistics

### Live viewing

**ES01**: As a Player, I want to view my Cobbleputer execution statistics live in the Browser UI so that I can tell
how my program is behaving.

The view receives only the Player's own raw per-tick instrumentation and aggregates it in a Browser-local
sliding window of 600 received ticks: offered events per execution sync, accepted and dropped events, effect
attempts, failures and timing, and the current number of loaded running and stopped Cobbleputers. It identifies
the newest sampling time and local-window coverage, including observed gaps, and indicates when no report is
available or the displayed report is stale. It includes one responsive 200-pixel-high canvas history, where each
received tick is a right-aligned pixel column, events extend upward from its middle, effects extend downward, and color
distinguishes accepted from dropped events and successful from failed effects. Hovering the canvas shows the selected
half's scale value at the pointer. It must not expose another Player's statistics.
