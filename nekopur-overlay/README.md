# Nekopur Overlay (Tree Patch)

Nekopur uses a tree overlay instead of patch files for its custom server/API changes.
These files are copied into the upstream sources during the build, after upstream
patches are applied. This keeps upstream syncs clean while ensuring Nekopur changes
always win.

## Where to edit
- Server / CraftBukkit changes: nekopur-overlay/paper-server/...
- API changes (if needed): nekopur-overlay/paper-api/...

## How it is applied
Gradle tasks in `purpur-server/build.gradle.kts` copy overlays into `paper-server`
and `paper-api` before compile tasks. If upstream patches run, overlays are applied
afterwards so they are not overwritten.

## Notes
- Do not edit `paper-server` directly for Nekopur features; those changes will be
  overwritten on upstream updates.
- Keep overlay files minimal and scoped to Nekopur-specific behavior.