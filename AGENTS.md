# AGENTS.md

The following documents serve as high-level specifications.
Whenever you start a task, consult the ones that seem likely to be relevant.
These specifications MUST be kept in sync with project implementation.
They are the source of truth -- when the implementation and spec differ,
assume the implementation is wrong unless specifically instructed otherwise.

* [Gameplay](docs/gameplay.md) describes intended gameplay mechanics, at the level a player would encounter/understand them.
* [Cobbleware Mod](docs/mod.md) describes mod configuration and command syntax.
* [Use Cases](docs/usecases.md) gives a more formal, labeled set of use cases that describe functionality
  without specifying concrete design/implementation details.
* [Architecture](docs/architecture.md) describes the high-level system architecture.
* [Main Worker API](docs/api.md) describes the Main Worker API, both the Mod-facing portion and the Player-facing portion.
* [Program API](docs/program-api.md) describes the API presented by a Program to the Main Worker. The cobbleware TS library
  provides reusable code for hosting this API, so Players generally do not need this information. It serves as a spec
  for mod developers.

When working in the mc-edge-runtime submodule, be sure to check its AGENTS.md as well.

When instructed to create a Git worktree, create it under the repository's `worktrees/` directory.

After every change in `java/cobbleware`, run `./gradlew.bat spotlessApply` from that directory.

When working on the Mod, always keep this key invariant in mind: The `setChanged` method can never be called on a code path
that runs during chunk load, because `setChanged` will attempt to load the containing chunk, which takes a lock that
is not reentrant (and so will deadlock).
