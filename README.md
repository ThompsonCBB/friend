# friend
<<<<<<< HEAD

Forge 1.20.1 psychological horror mod.

Friend is an observer entity. It does not naturally spawn, pathfind, chase, or behave like a normal hostile mob. Server events track player isolation, darkness, underground time, and a learned home location, then trigger rare sound and sighting events.

## Build

Install JDK 17, then run:

```powershell
gradle build
```

If you do not have Gradle installed, import the folder into IntelliJ IDEA as a Gradle project, or generate a Gradle wrapper with a local Gradle install:

```powershell
gradle wrapper --gradle-version 8.8
.\gradlew build
```

This machine currently reports Java 8 as default, so Forge 1.20.1 will not compile until JDK 17 is selected.

## Debug commands

Operator-only:

- `/friend debug`
- `/friend status`
- `/friend reset`
- `/friend phase <0-5>`
- `/friend event <event_id>`

## Assets

The included texture is a generated placeholder designed around the mask-face concept. Replace it with final Blockbench texture art before release if you want a more polished model.

The included OGG files are original synthesized placeholder sounds generated for this project, so they are legal to ship as placeholders. For a stronger final release, replace them with self-recorded or properly licensed CC0/royalty-free sounds. See `ASSET_SOURCES.md`.
=======
Psychological horror mod for Minecraft Forge 1.20.1 focused on stalking, environmental fear and adaptive AI behavior. Friend watches the player, hides in caves, suppresses lights, performs dynamic peek events and becomes more aggressive over time instead of relying on constant cheap attacks.
>>>>>>> bf1ffa314f66d8f299ada01ca68c45fc4abcf477
