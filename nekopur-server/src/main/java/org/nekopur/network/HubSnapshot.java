package org.nekopur.network;

import org.jspecify.annotations.NullMarked;

/** Explicit hub-position profile. Inventories, XP and plugin data are intentionally excluded. */
@NullMarked
public record HubSnapshot(String worldIdentity, String mapRevision, double x, double y, double z,
                          float yaw, float pitch, double velocityX, double velocityY, double velocityZ) {
    public HubSnapshot {
        if (worldIdentity == null || !worldIdentity.matches("[a-z0-9][a-z0-9_-]{0,63}")
            || mapRevision == null || !mapRevision.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("A world identity and map revision are required");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
            || Math.abs(x) > 29_999_984 || Math.abs(z) > 29_999_984 || Math.abs(y) > 20_000_000
            || !Float.isFinite(yaw) || !Float.isFinite(pitch) || Math.abs(pitch) > 90
            || !Double.isFinite(velocityX) || !Double.isFinite(velocityY) || !Double.isFinite(velocityZ)
            || Math.abs(velocityX) > 100 || Math.abs(velocityY) > 100 || Math.abs(velocityZ) > 100) {
            throw new IllegalArgumentException("Invalid hub snapshot coordinates or velocity");
        }
    }
}
