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

The Fabric Mod currently creates `config/cobbleware.json` on first start.

### Configuration reference

| Setting | Default | Description |
| --- | --- | --- |
| `instrumentationWindowTicks` | `600` | Positive integer number of server ticks retained in the in-memory instrumentation window (30 seconds at 20 ticks per second). Existing configuration files may omit it. |
| `mainWorkerApiKey` | Empty string | Required string shared with the Main Worker's `COBBLEWARE_API_KEY` for external/manual Runtimes. It is ignored, with a warning, when the Mod starts a managed Runtime because that process receives a fresh in-memory key. |
| `mainWorkerUrl` | `http://localhost:9000` | Required absolute HTTP or HTTPS Main Worker base URL, without credentials, query, or fragment. A path is allowed. |
| `runtimeMode` | `auto` | Optional lifecycle mode. A release JAR with bundled workers and a Runtime release pin downloads, stages, and manages a local Runtime; a development JAR without them continues to use an externally started Runtime. Use `external` to opt out or `managed` to require the packaged resources. |
| `runtimePort` | `9000` | Integer from 1 through 65535 used by a Mod-managed Runtime listening on loopback. |
| `maxRunningExecutionsPerPlayer` | `10` | Positive integer cap on each player's block-ticking `RUNNING` Cobbleputer executions across all dimensions. Starting or resuming beyond it is rejected; a running execution is stopped when its chunk stops block ticking and resumes when it starts block ticking again only when capacity permits. Existing configuration files may omit it. |
| `maxTotalExecutionStateBytesPerPlayer` | `1048576` | Positive integer cap, in bytes, on all durable Cobbleputer execution-state arrays owned by one player. Running, stopped, crashed, and unloaded executions count; events, effects, and protobuf/file overhead do not. The Mod derives a per-execution state-size allowance for each Runtime sync, and discards/crashes an execution whose returned state would exceed the authoritative total. Existing configuration files may omit it. |

Until Mod development progress farther, the following is aspirational rather than descriptive.

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
