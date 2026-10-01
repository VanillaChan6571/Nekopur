# Nekopur Overlay (NOT WIRED INTO THE BUILD)

**This overlay is not applied by anything.** An earlier revision of this file described a tree
overlay copied into the upstream sources during the build, and told readers not to edit
`paper-server` directly. That mechanism does not exist in this build:

- `:purpur-server:applyNekopurOverlay` is not a registered task (`gradlew` reports
  "task 'applyNekopurOverlay' not found in project ':purpur-server'").
- No build script references `nekopur-overlay` or `NekopurOverlay`.
- `scripts/apply-nekopur-overlay.sh` therefore always fails.

The files under `paper-server/` here are kept because they are real edits someone made, but they
are **not** part of a build. Do not assume a change placed here takes effect.

## Snapshot revision

The 23 Java snapshots were refreshed from the fully patched 26.3 server tree, based on
Purpur `0b28be28ee15b894733bd6a73a644f9db59ff9c3` and Paper
`0fdc08858f78b03c1df8033fedf90117e8c7d5a9`. They include this branch's existing
compatibility patches and remain reference snapshots; the build uses the tracked
patches and Nekopur sources.

## Where Nekopur changes actually go

Run `./gradlew nekopurWhere`, which prints the current layout and what remains unresolved.

In short: Nekopur's own classes live in `nekopur-server/src/main/java/org/nekopur/...` and are
ordinary tracked sources. Changes to `net.minecraft` classes appear in
`purpur-server/minecraft-patches/sources/...` as `// Nekopur` hunks, and the tree that gets
compiled is `purpur-server/src/minecraft/java`.

## Capturing a net.minecraft edit

Run `./gradlew nekopurWhere` for the current workflow. In short, after editing
`purpur-server/src/minecraft`:

```
./gradlew :purpur-server:fixupMinecraftSourcePatches
./gradlew :purpur-server:rebuildMinecraftSourcePatches
```

`rebuildMinecraftSourcePatches` on its own does nothing, because it stashes working changes and
checks out the `file` commit before exporting. `fixup` commits the edit into that commit first.
