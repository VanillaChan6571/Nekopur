# Nekopur Async World Creation API

Nekopur provides a truly asynchronous world creation API that minimizes main thread impact, making it ideal for minigame servers that need to create worlds dynamically without causing TPS drops.

## Quick Start

```java
import org.nekopur.Neko;
import org.bukkit.WorldCreator;

// Simple async world creation
Neko.createWorldAsync(new WorldCreator("my_world"))
    .thenAccept(world -> {
        if (world != null) {
            getLogger().info("World ready: " + world.getName());
        }
    });
```

## Why Use This Instead of Bukkit.createWorld()?

| Method | Main Thread Impact |
|--------|-------------------|
| `Bukkit.createWorld()` | Blocks entirely - causes lag spikes |
| `Bukkit.createWorldAsync()` | Still blocks for I/O operations |
| `Neko.createWorldAsync()` | Minimal - only essential registration on main thread |

### What Runs Async

- Directory creation
- Template file copying (level.dat, region files)
- Spawn chunk loading (via Paper's async chunk API)
- Optional chunk pre-generation (if `preGenerateRadius` is set)

### What Runs on Main Thread (Required by Minecraft)

- ServerLevel construction (minimal, fast)
- World registration

## API Classes

### `Neko`

Static entry point for Nekopur APIs.

```java
// With default options
CompletableFuture<World> future = Neko.createWorldAsync(WorldCreator creator);

// With custom options
CompletableFuture<World> future = Neko.createWorldAsync(WorldCreator creator, AsyncWorldOptions options);
```

### `AsyncWorldOptions`

Configure world creation behavior using the builder pattern.

```java
AsyncWorldOptions options = AsyncWorldOptions.builder()
    .spawnChunks(SpawnChunkBehavior.SKIP)           // How to handle spawn chunks
    .template(WorldTemplate.fromWorld(templateDir)) // Optional template
    .progressCallback(this::onProgress)             // Optional progress updates
    .generateSpawn(true)                            // Generate spawn point
    .build();
```

Defaults:
- `spawnChunks`: `SKIP`
- `generateSpawn`: `false`
- `preGenerateRadius`: `0` (disabled)

#### SpawnChunkBehavior

| Option | Description | Use Case |
|--------|-------------|----------|
| `SKIP` | Don't load spawn chunks | Fastest - for worlds where spawn doesn't matter |
| `ASYNC_FIRE_AND_FORGET` | Load async, return immediately | Good balance |
| `ASYNC_WAIT` | Load async, wait for completion | When spawn chunks must be ready |

#### Pre-generation

Use pre-generation when you need a playable area immediately (e.g., UHC).

```java
AsyncWorldOptions options = AsyncWorldOptions.builder()
    .preGenerateRadius(32)  // Radius in chunks
    .preGenerateCallback(progress -> {
        getLogger().info("Pre-gen: " + (int) (progress.getProgress() * 100) + "%");
    })
    .build();
```

### `WorldTemplate`

Use templates to copy pre-built worlds instead of generating from scratch.

```java
// Full world copy (level.dat + terrain + entities)
WorldTemplate template = WorldTemplate.fromWorld(new File("templates/arena"));

// Just level.dat (for void worlds or fresh generation with preset settings)
WorldTemplate template = WorldTemplate.levelDatOnly(new File("templates/void"));

// Custom configuration
WorldTemplate template = WorldTemplate.builder(templatePath)
    .copyRegionFiles(true)   // Terrain data
    .copyEntities(true)      // Entities
    .copyPoi(true)           // Points of interest (villager workstations)
    .copyDatapacks(false)    // Datapacks
    .build();
```

### `AsyncWorldProgress`

Track creation progress via callback.

```java
AsyncWorldOptions.builder()
    .progressCallback(progress -> {
        getLogger().info(String.format("[%s] %s",
            progress.getWorldName(),
            progress.getStage().getDescription()));
    })
    .build();
```

#### Progress Stages

| Stage | Description |
|-------|-------------|
| `CREATING_DIRECTORY` | Creating world folder |
| `COPYING_TEMPLATE` | Copying template files (if using template) |
| `CREATING_STORAGE` | Setting up storage access |
| `LOADING_WORLD_DATA` | Loading or creating world data (level.dat) |
| `PARSING_WORLD_DATA` | Parsing world data NBT |
| `CREATING_GENERATOR` | Creating level stem and chunk generator |
| `CONSTRUCTING_LEVEL` | Building ServerLevel (main thread) |
| `REGISTERING_WORLD` | Registering with server |
| `LOADING_SPAWN_CHUNKS` | Loading spawn chunks async |
| `PRE_GENERATING_CHUNKS` | Pre-generating chunks async |
| `COMPLETED` | World is ready |
| `FAILED` | Creation failed |

## Complete Examples

### Minigame Arena from Template

```java
public CompletableFuture<World> createArenaInstance(String arenaName, int instanceId) {
    String worldName = arenaName + "_" + instanceId;
    File templateDir = new File(getDataFolder(), "templates/" + arenaName);

    return Neko.createWorldAsync(
        new WorldCreator(worldName)
            .environment(World.Environment.NORMAL)
            .type(WorldType.FLAT),
        AsyncWorldOptions.builder()
            .template(WorldTemplate.fromWorld(templateDir))
            .spawnChunks(SpawnChunkBehavior.SKIP)
            .progressCallback(p -> getLogger().info(
                "[" + worldName + "] " + p.getStage().getDescription()))
            .build()
    );
}

// Usage
createArenaInstance("bedwars", 1).thenAccept(world -> {
    // Teleport players, start game, etc.
    for (Player player : gamePlayers) {
        player.teleport(world.getSpawnLocation());
    }
});
```

## World Pool Monitoring Command

Nekopur includes a built-in command to inspect world pool status and verify worlds are actually created.

```
/purrworlds
/purrworlds list
/purrworlds hop <world>
```

What it shows:
- Active: Worlds currently in use
- Waiting: Available + creating + queued acquires
- Deleted/Disabled: Recently discarded worlds and pool shutdown state

Notes:
- `/purrworlds hop <world>` only works for loaded worlds (it does not auto-load).
- It also lists active worlds that are not managed by any pool.

## World Pool Status API

Plugins can inspect pool state directly without relying on the command:

```java
WorldPool pool = Neko.getServer().getPool("arena");
if (pool != null) {
    WorldPoolStatus status = pool.getStatus();
    List<String> active = status.getActiveWorlds();
    List<String> available = status.getAvailableWorlds();
    List<String> creating = status.getCreatingWorlds();
    int waiting = status.getWaitingAcquireCount();
    List<String> deleted = status.getDeletedWorlds();
    boolean shutdown = status.isShutdown();
}
```

## Async Unload API

Unload worlds without blocking the main thread.

```java
// Quick discard (no save, delete files)
Neko.getServer().unloadWorldAsync(world, UnloadOptions.discard());

// Safe unload (save, keep files)
Neko.getServer().unloadWorldAsync(world, UnloadOptions.safe());
```

Defaults for `UnloadOptions`:
- `saveChunks`: `true`
- `deleteWorldFolder`: `false`

### Void World for Building

```java
public CompletableFuture<World> createVoidWorld(String name) {
    return Neko.createWorldAsync(
        new WorldCreator(name)
            .generator(new VoidGenerator())
            .environment(World.Environment.NORMAL),
        AsyncWorldOptions.builder()
            .spawnChunks(SpawnChunkBehavior.SKIP)
            .generateSpawn(false)
            .build()
    );
}
```

### Batch World Creation

```java
public CompletableFuture<List<World>> createMultipleWorlds(int count) {
    List<CompletableFuture<World>> futures = new ArrayList<>();

    for (int i = 0; i < count; i++) {
        futures.add(Neko.createWorldAsync(
            new WorldCreator("game_world_" + i),
            AsyncWorldOptions.builder()
                .template(WorldTemplate.fromWorld(templateDir))
                .spawnChunks(SpawnChunkBehavior.SKIP)
                .build()
        ));
    }

    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
        .thenApply(v -> futures.stream()
            .map(CompletableFuture::join)
            .filter(Objects::nonNull)
            .collect(Collectors.toList()));
}
```

## Maven/Gradle Dependency

Add the Nekopur API as a compile-only dependency:

```xml
<!-- Maven -->
<dependency>
    <groupId>org.nekopur</groupId>
    <artifactId>nekopur-api</artifactId>
    <version>1.21.11-R0.1-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>
```

```kotlin
// Gradle (Kotlin DSL)
compileOnly("org.nekopur:nekopur-api:1.21.11-R0.1-SNAPSHOT")
```

## Best Practices

1. **Use Templates**: For minigames, always use templates instead of generating worlds from scratch. It's significantly faster.

2. **Skip Spawn Chunks**: Unless players spawn immediately, use `SpawnChunkBehavior.SKIP` for fastest creation.

3. **Handle Failures**: Always handle the future properly:
   ```java
   Neko.createWorldAsync(creator, options)
       .thenAccept(world -> {
           if (world != null) {
               // Success
           } else {
               // Handle null (shouldn't happen normally)
           }
       })
       .exceptionally(ex -> {
           getLogger().severe("World creation failed: " + ex.getMessage());
           return null;
       });
   ```

4. **Clean Up Worlds**: Remember to unload and delete temporary worlds when done:
   ```java
   Bukkit.unloadWorld(world, false); // false = don't save
   deleteWorldFolder(world.getWorldFolder());
   ```

## Thread Safety

- The `CompletableFuture` callbacks may run on async threads
- Use `Bukkit.getScheduler().runTask()` for any Bukkit API calls in callbacks that require the main thread
- The returned `World` object is safe to use from any thread for most operations
