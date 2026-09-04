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

TBD

## Configuration

Until Mod development progress farther, this section is aspirational rather than descriptive.

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

### Building

### Testing
