# Rollback

Server-side Fabric mod that rolls the whole server back to a past moment: journals changed chunks and player data every minute, restores with a countdown, and rolls back automatically on overload or hang.

## Features

- Journals changed chunks and player/world data every minute.
- `/backup <time>` restores the server to that moment, with a countdown.
- Automatic rollback when the server is overloaded or hangs.
- Public API and events for other mods.

## Commands

| Command | What it does |
|---|---|
| `/backup <time>` | Restore the server to that moment |
| `/backup list` | Show available restore points |
| `/backup now` | Make a restore point right away |
| `/backup status` | Show the journal state |
| `/backup cancel` | Cancel a pending rollback |
| `/backup reload` | Reload the config |

## Compatibility

Minecraft 1.21.1, Fabric Loader and Fabric API. Runs on the server; players need no client mod.

## Build

```bash
./gradlew build
```

The jar appears in `build/libs/`.

## License

All Rights Reserved.
