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
