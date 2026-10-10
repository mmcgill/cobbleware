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

On Windows, the release build downloads the pinned Edge Runtime to bundle the Main Worker and Event Worker, then
embeds those worker ESZips and the pinned Runtime GitHub Release description in a separate release JAR:

```powershell
.\gradlew.bat releaseBuild
```

This requires Deno. The resulting
`build/libs/*-release.jar` contains these resources:

```text
cobbleware/runtime/manifest.json
cobbleware/workers/main-worker.eszip
cobbleware/workers/event-worker.eszip
```

`releaseBuild` is intentionally separate from `build`; ordinary Fabric development builds do not build workers.
`runServer` does generate those resources by default, downloading the pinned Runtime for worker bundling and again
for the Mod's verified world cache as necessary. Add `-PuseLocalRuntime` to `runServer` or `releaseBuild` to build
the local Windows `mc-edge-runtime` checkout instead; that requires Rust and the Visual Studio C++ workload. At
server startup, a release JAR downloads the pinned native Runtime asset for Windows or Linux x86_64 only if a
SHA-256-verified copy is not already under the world's `cobbleware-data/runtime` directory. This preparation is
asynchronous, so a slow initial download does not block server startup; the server log records its start and
checksum-verified completion. To update that pin,
first publish and verify an Edge Runtime release, then review and copy its tag, asset names, and checksums into
`build.gradle`.

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

This starts a managed Runtime through the pinned-download path. For Edge Runtime checkout development, use
`.\gradlew.bat runServer -PuseLocalRuntime` to build and stage the local Windows binary instead.

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

`runtimeMode` defaults to `auto` when omitted. A release JAR containing packaged workers and a
pinned Runtime release downloads its supported Windows or Linux x86_64 asset when no verified copy
is cached under the world's `cobbleware-data/runtime` directory, then manages it for the Minecraft
server lifetime; the ordinary development JAR has no packaged Runtime, so `auto` continues to use a
manually run process. Set `runtimeMode` to `external` to opt out, `managed` to require the pinned
Runtime. `runtimePort` is the optional loopback port for a managed Runtime and defaults to 9000.

For a managed Runtime, Cobbleware creates a fresh in-memory `COBBLEWARE_API_KEY` on every server
start and uses it for its own requests. It does not write that key to this file. If
`mainWorkerApiKey` is configured in a managed mode, it is ignored and Cobbleware logs a warning;
the setting is still used for external/manual Runtimes.

Open the Main Worker's browser UI, then run its displayed
`/cobbleware auth <code>` command in-game. On success, the next browser poll completes
the session login.
