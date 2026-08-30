# Use Cases

## Programs

### Viewing

**PG01**: As a Player, I want to view the programs that I have stored on a Server and their sizes, so that I can
tell how much space for programs I have remaining.

A Program is an ESZip bundle and associated metadata. Programs belong to players.
A program has the following metadata:

* program id (uniquely identifies the given program on the server that contains it)
* program name (chosen by the user, or derived from the uploaded filename)
* upload date
* size in bytes

A player can view only their programs. A player cannot see other players' programs.

### Uploading

**PG02**: As a Player, I want to upload a new program from my computer so that I can run it on Cobbleputers.

The player provides the ESZip bundle for the program, normally by dragging and dropping an ESZip file into
the Browser UI. The player may optionally provide a program name; if they do not, the server uses the uploaded
file's basename, retaining its extension. The server removes directory components from the filename before
using it as a name.

The server auto-assigns a program id upon successful upload and atomically stores the program and its metadata.
The uploaded bytes are treated as opaque at upload time; validation is deferred until the program is loaded for
execution. After uploading, the ESZip payload size counts against the player's size quota. An unsuccessful
upload does not create a program or consume quota.

### Removing

**PG03**: As a Player, I want to remove an old program to free up space for new programs.

The player specifies the id of the program to be removed. On successful deletion, the program is
fully deleted from the server and no longer counts against the player's program quota.

A player can delete only their programs, and not the programs of other players.
