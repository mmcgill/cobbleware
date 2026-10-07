# Cobbleware Fabric Mod

This is the Fabric mod for Minecraft 26.2. It is written for Java 25 and uses the unobfuscated Minecraft source names
introduced with Minecraft 26.1.

## Build

```powershell
.\gradlew.bat build
```

The mod JAR is written to `build/libs/`; use the JAR with the shortest filename outside the development
environment.

### Windows release build

On Windows, the self-contained release build compiles the Edge Runtime, bundles the Main Worker and Event Worker,
then embeds them in a separate release JAR:

```powershell
.\gradlew.bat releaseBuild
```

This requires Deno, Rust, and the Visual Studio C++ workload. The Runtime build task
initializes the repository's `mc-edge-runtime/scripts/windows-env.cmd` environment before running Cargo with local AI
inference disabled. The resulting
`build/libs/*-release.jar` contains these resources:

```text
cobbleware/runtime/windows-x86_64/edge-runtime.exe
cobbleware/runtime/manifest.json
cobbleware/workers/main-worker.eszip
cobbleware/workers/event-worker.eszip
```

`releaseBuild` is intentionally separate from `build`; ordinary Fabric development builds and run configurations do
not build Rust or Deno artifacts. Linux and macOS resource paths will follow the same `cobbleware/runtime/<platform>/`
layout when their native build lanes are added.

## Formatting

After every change, run the Palantir Java Format through Spotless:

```powershell
.\gradlew.bat spotlessApply
```

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

The development server continues to use `run` and is configured with `online-mode=false`, so the named development clients
can join without Mojang authentication. Start each client in its own run directory with:

```powershell
.\gradlew.bat runAlice
.\gradlew.bat runBob
```

On a fresh development server, Minecraft creates `run/eula.txt`. Accept Mojang's EULA by setting `eula=true` before
starting the server again. The scaffold's `/cobbleware startstates` command currently responds with `hello world`.

## Server-only deployment

Cobbleware is installed on the server only. Deploy its JAR together with Fabric API and the matching
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

Set `mainWorkerApiKey` to the same value used for an external Main Worker's
`COBBLEWARE_API_KEY`. The Mod logs an error and disables authentication requests while
the value is empty only when it is not starting a managed Runtime. `mainWorkerUrl` may use either
`http` or `https`; it points to an already-running external Main Worker. `instrumentationWindowTicks`
retains in-memory Mod instrumentation for that many server ticks; it defaults to 600 when omitted
from an existing configuration file.

`runtimeMode` defaults to `auto` when omitted. A release JAR containing a supported bundled Runtime
unpacks it into the world's `cobbleware-data/runtime` directory and manages it for the Minecraft
server lifetime; the ordinary development JAR has no bundled Runtime, so `auto` continues to use a
manually run process. Set `runtimeMode` to `external` to opt out, `managed` to require the bundled
Runtime, or `extract-only` to unpack without launching. `runtimePort` is the optional loopback port
for a managed Runtime and defaults to 9000.

For a managed Runtime, Cobbleware creates a fresh in-memory `COBBLEWARE_API_KEY` on every server
start and uses it for its own requests. It does not write that key to this file. If
`mainWorkerApiKey` is configured in a managed mode, it is ignored and Cobbleware logs a warning;
the setting is still used for external/manual Runtimes.

Open the Main Worker's browser UI, then run its displayed
`/cobbleware auth <code>` command in-game. On success, the next browser poll completes
the session login.
