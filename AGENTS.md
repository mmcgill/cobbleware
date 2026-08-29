# AGENTS.md

The following documents serve as high-level specifications.
Whenever you start a task, consult the ones that seem likely to be relevant.
These specifications MUST be kept in sync with project implementation.
They are the source of truth -- when the implementation and spec differ,
assume the implementation is wrong unless specifically instructed otherwise.

* [Gameplay](docs/gameplay.md) describes intended gameplay mechanics, at the level a player would encounter/understand them.
* [Architecture](docs/architecture.md) describes the high-level system architecture.
* [Use Cases](docs/usecases.md) gives a more formal, labeled set of use cases that describe functionality
  without specifying concrete design/implementation details.
* [Main Worker API](docs/api.md) describes the Main Worker API, both the Mod-facing portion and the Player-facing portion.

When working in the mc-edge-runtime submodule, be sure to check its AGENTS.md as well.