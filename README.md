# Cobbleware Minecraft Mod

Cobble together (ha!) software systems in Minecarft!

## Goals

Goals for the mod:

* Players can use the JS tooling and library ecosystem to build integrations with external services,
  automation, interactivity, etc.
* The Mod can be safely deployed on public servers.
  * A player cannot exploit the Mod to cause denial of service for other players, or third-party services.
  * Server admins have sufficient visibility into players' use of the Mod to detect abusive activities.

Personal goals:

* Learn the guts of the Supabase Edge Runtime.

## Status

The Mod is in currently in early stages of design and development, and is not in a playable state.

## Installation

Cobbleware is a server-only Fabric mod. Install Fabric API, Cobbleware, and the matching
`polymer-bundled-0.17.4+26.2` JAR in the server's `mods/` directory. Players use a normal,
unmodded Minecraft 26.2 client; they do not install Cobbleware or Polymer.

Cobbleware requires Polymer's generated resource pack so vanilla clients can render Cobbleputers.
After installing the mods, start the server once, set `"enabled": true` in
`config/polymer/auto-host.json`, then restart. On a direct server AutoHost reuses the game port;
for proxies or a separate HTTP port, configure `forced_address` or the HTTP settings documented by
Polymer. Players who decline the resource pack cannot join.

Use `/polymer generate-pack` to create `polymer/resourcepack.zip` for diagnostics or manual
hosting.

## Configuration

Until Mod development progress farther, this section is aspirational rather than descriptive.

### Current Main Worker connection settings

The Fabric Mod currently creates `config/cobbleware.json` on first start. Set
`mainWorkerApiKey` to the same value supplied to the already-running Main Worker as
`COBBLEWARE_API_KEY`; leave `mainWorkerUrl` at its default `http://localhost:9000` or
set it to that worker's HTTP/HTTPS base URL. The API key defaults to an empty string,
which causes the Mod to log an error and disable authentication requests until an
administrator sets it. `instrumentationWindowTicks` controls the in-memory
instrumentation retention window in server ticks and defaults to 600 (30 seconds).

The Mod will provide server admins with the following configuration options:

* A cap on the total number of V8 isolates that may be active at any given time.
* A flag controlling whether or not Cobbleputers execute when their owning players
  are not logged in.
* A flag controlling whether or not network access is allowed.
* An optional network allow-list of hosts that players are permitted to connect to.
* A per-player limit on the number of initiated network connections per minute.
* A per-player limit on bytes sent over the network.
* A per-player limit on bytes received over the network.
* A per-player limit on the total size (in bytes) available for program storage, across all programs.
* A per-player limit on the number of Cobbleputers.
* A per-Cobbleputer limit on runtime state size.


## Development

### Prerequisites

You'll need the following prerequisites:

* Protocol Buffers Compiler (`protoc`). See the [Protocol Buffers installation guide](https://protobuf.dev/installation/)
for installation methods.
* JDK 25 for the Fabric mod in [`java/cobbleware`](java/cobbleware/).

### Building

Build the Fabric mod from its project directory:

```powershell
cd java/cobbleware
.\gradlew.bat build
```

### Testing

Start a development server with:

```powershell
cd java/cobbleware
.\gradlew.bat runServer
```

Minecraft creates `java/cobbleware/run/eula.txt` on the first launch. A developer who accepts Mojang's EULA must set
`eula=true` there before the server can finish starting. The current scaffold registers `/cobbleware startstates`,
which prints `hello world`.
