# AssemblyLib

A standalone NeoForge library mod (Minecraft 1.21.1) providing **block-entity-hosted assemblys**:
assemble a structure, move and rotate it, and interact with the blocks it carries while it rides a
host block entity. The **Servo Motor** is the first driver.

Extracted from [Zero Point Systems](../ZeroPointSystems). The assembly core does not depend on
Create (`com.simibubi`); it uses Flywheel for rendering and Catnip's wrapped levels for the
server-side simulation.

## What's inside
- `com.assemblylib.assembly` — assembly model, simulation levels, transforms, collision
- `com.assemblylib.block` / `blockentity` — the Servo Motor block and its block entity
- `com.assemblylib.client` — Flywheel visuals, block-entity renderer, client-side interaction
- `com.assemblylib.networking` — assembly place/break/use packets
- `com.assemblylib.mixin` — vanilla hooks (falling block rotation, menus, level access, …)
- `com.assemblylib.gametest` — servo motor, nesting, and redstone game tests

## Building
```
./gradlew build
```

## Running game tests
```
./gradlew gameTestServer
```
