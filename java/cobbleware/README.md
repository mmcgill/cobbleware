# Cobbleware Fabric Mod

This is the Fabric mod for Minecraft 26.2. It is written for Java 25 and uses the unobfuscated Minecraft source names
introduced with Minecraft 26.1.

## Build

```powershell
.\gradlew.bat build
```

The remapped mod JAR is written to `build/libs/`; use the JAR with the shortest filename outside the development
environment.

## Protocol Buffers

The Java bindings are generated from the repository-wide schemas in [`../../protobuf`](../../protobuf). Generate them
explicitly with:

```powershell
.\gradlew.bat generateCobblewareProtos
```

Generation is also wired into Java compilation. Gradle regenerates bindings when a schema changes; append
`--rerun-tasks` to force generation when the schemas are unchanged.

## Development server

```powershell
.\gradlew.bat runServer
```

On a fresh development server, Minecraft creates `run/eula.txt`. Accept Mojang's EULA by setting `eula=true` before
starting the server again. The scaffold's `/cobbleware startstates` command currently responds with `hello world`.

## Server-only deployment

Cobbleware is installed on the server only. Deploy its remapped JAR together with Fabric API and the matching
`polymer-bundled-0.17.4+26.2` JAR; clients need neither mod. Polymer supplies vanilla client representations and a
required resource pack for Cobbleputers.

Start the server once, set `"enabled": true` in `config/polymer/auto-host.json`, and restart to have Polymer AutoHost
generate and offer the pack. Direct servers can reuse the game port. Configure `forced_address` or a separate HTTP
listener in that file when deploying behind a proxy. `/polymer generate-pack` writes a diagnostic/manual-hosting pack
to `polymer/resourcepack.zip`.

## Main Worker configuration

On first startup, the Mod creates `config/cobbleware.json` in Fabric's config directory:

```json
{
  "mainWorkerUrl": "http://localhost:9000",
  "mainWorkerApiKey": "",
  "instrumentationWindowTicks": 600
}
```

Set `mainWorkerApiKey` to the same value used for the Main Worker's
`COBBLEWARE_API_KEY`. The Mod logs an error and disables authentication requests while
the value is empty. `mainWorkerUrl` may use either `http` or `https`; it points to an
already-running Main Worker. `instrumentationWindowTicks` retains in-memory Mod
instrumentation for that many server ticks; it defaults to 600 when omitted
from an existing configuration file.

Open the Main Worker's browser UI, then run its displayed
`/cobbleware auth <code>` command in-game. On success, the next browser poll completes
the session login.
