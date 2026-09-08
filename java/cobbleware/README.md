# Cobbleware Fabric Mod

This is the Fabric mod for Minecraft 26.2. It is written for Java 25 and uses the unobfuscated Minecraft source names
introduced with Minecraft 26.1.

## Build

```powershell
.\gradlew.bat build
```

The remapped mod JAR is written to `build/libs/`; use the JAR with the shortest filename outside the development
environment.

## Development server

```powershell
.\gradlew.bat runServer
```

On a fresh development server, Minecraft creates `run/eula.txt`. Accept Mojang's EULA by setting `eula=true` before
starting the server again. The scaffold's `/cobbleware startstates` command currently responds with `hello world`.
