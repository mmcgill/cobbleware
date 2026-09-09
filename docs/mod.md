# Cobbleware Mod

This document describes configuration and usage for the Cobbleware mod itself.

## Slash Commands

### Authentication

```
/cobbleware authenticate <code>
```

This command is run with a `<code>` provided to the user through their web browser. It serves
to tie a browser session to an already-authenticated Player logged into the Minecraft server.

### Program

```
/cobbleware program
```

Prints information (e.g. name, size, names of start states) about the player's currently active program, if
there is one.

