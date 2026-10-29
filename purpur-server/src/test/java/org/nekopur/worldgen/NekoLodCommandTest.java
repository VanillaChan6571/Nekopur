package org.nekopur.worldgen;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.nekopur.command.NekoLodCommand;
import static org.junit.jupiter.api.Assertions.*;

@NullMarked
final class NekoLodCommandTest {
    @Test
    void accurateModeSupportsBothSendersAndImplicitCrossDetailRefinement() {
        var player = NekoLodCommand.parsePrewarm(new String[] {"prewarm", "0", "0", "256", "10", "accurate"}, "world");
        assertTrue(player.accurate());
        assertTrue(player.allowUpgrade());
        assertEquals(64, NekoLodCommand.plan(player.x(), player.z(), player.radius(), player.detail()).size());
        var console = NekoLodCommand.parsePrewarm(new String[] {"prewarm", "world", "0", "0", "16", "AcCuRaTe"}, null);
        assertEquals(10, console.detail());
        assertTrue(console.accurate());
        assertTrue(NekoLodCommand.parsePrewarm(new String[] {"prewarm", "world", "0", "0", "16", "9", "allowUpgrade", "accurate"}, null).accurate());
        assertTrue(NekoLodCommand.parsePrewarm(new String[] {"prewarm", "world", "0", "0", "16", "9", "accurate", "allowUpgrade"}, null).accurate());
        assertFalse(NekoLodCommand.parsePrewarm(new String[] {"prewarm", "world", "0", "0", "16", "allowUpgrade"}, null).accurate());
        assertFalse(NekoLodCommand.parsePrewarm(new String[] {"prewarm", "world", "0", "0", "16"}, null).accurate());
        assertThrows(IllegalArgumentException.class, () -> NekoLodCommand.parsePrewarm(new String[] {"prewarm", "0", "0", "16", "accurate"}, null));
        assertThrows(IllegalArgumentException.class, () -> NekoLodCommand.parsePrewarm(new String[] {"prewarm", "world", "0", "0", "16", "accurate", "accurate"}, null));
        assertThrows(IllegalArgumentException.class, () -> NekoLodCommand.parsePrewarm(new String[] {"prewarm", "world", "0", "0", "16", "accurate", "9"}, null));
    }
    @Test
    void prewarmPlanCoversNegativeCoordinatesAndPrioritizesNearTiles() {
        List<NekoLodCommand.Tile> tiles = NekoLodCommand.plan(-1, -1, 64, 10);
        assertEquals(9, tiles.size());
        assertEquals(new NekoLodCommand.Tile(-1024, -1024), tiles.getFirst());
        assertTrue(tiles.contains(new NekoLodCommand.Tile(-2048, -2048)));
        assertTrue(tiles.contains(new NekoLodCommand.Tile(0, 0)));
        assertEquals(tiles.size(), new java.util.HashSet<>(tiles).size());
        tiles.forEach(tile -> { assertEquals(0, Math.floorMod(tile.x(), 1024)); assertEquals(0, Math.floorMod(tile.z(), 1024)); });
    }

    @Test
    void prewarmBoundsPreventUnboundedAndOverflowingRequests() {
        assertEquals(256, NekoLodCommand.plan(0, 0, 512, 10).size());
        assertThrows(IllegalArgumentException.class, () -> NekoLodCommand.plan(0, 0, 512, 7));
        assertThrows(IllegalArgumentException.class, () -> NekoLodCommand.plan(Integer.MAX_VALUE, 0, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> NekoLodCommand.plan(-30_000_000, 0, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> NekoLodCommand.plan(0, 0, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> NekoLodCommand.plan(0, 0, 1, 11));
    }

    @Test
    void upgradeFlagSupportsPlayerAndConsoleSyntaxWithoutChangingTileCoverage() {
        var player = NekoLodCommand.parsePrewarm(new String[] {"prewarm", "0", "0", "256", "10", "allowUpgrade"}, "world");
        assertEquals("world", player.worldName());
        assertTrue(player.allowUpgrade());
        assertEquals(64, NekoLodCommand.plan(player.x(), player.z(), player.radius(), player.detail()).size());
        var console = NekoLodCommand.parsePrewarm(new String[] {"prewarm", "world", "-1", "-2", "16", "allowUpgrade"}, null);
        assertEquals(10, console.detail());
        assertEquals(-1, console.x());
        assertTrue(console.allowUpgrade());
        assertFalse(NekoLodCommand.parsePrewarm(new String[] {"prewarm", "world", "0", "0", "1", "7"}, null).allowUpgrade());
        assertThrows(IllegalArgumentException.class, () -> NekoLodCommand.parsePrewarm(new String[] {"prewarm", "0", "0", "256", "10", "allowUpgrade"}, null));
        assertThrows(IllegalArgumentException.class, () -> NekoLodCommand.parsePrewarm(new String[] {"prewarm", "world", "0", "0", "1", "10", "unknown"}, null));
        assertThrows(IllegalArgumentException.class, () -> NekoLodCommand.parsePrewarm(new String[] {"prewarm", "world", "0", "0", "1", "allowUpgrade", "allowUpgrade"}, null));
    }

    @Test
    void prewarmFootprintAuthorizesActualRequestedDetailsRatherThanOnlyPlannedKeys() {
        UUID world = UUID.randomUUID();
        NekoLodService.RefinementPolicy policy = new NekoLodService.RefinementPolicy(128);
        var bounds = NekoLodCommand.refinementBounds(NekoLodCommand.plan(0, 0, 256, 10), 10);
        assertEquals(new NekoLodCommand.RefinementBounds(-4096, -4096, 4096, 4096), bounds);
        policy.register(world, bounds.minX(), bounds.minZ(), bounds.maxXExclusive(), bounds.maxZExclusive());
        for (int stride : new int[] {2, 4, 8, 16}) {
            assertTrue(policy.enabled(world, 0, 0, stride), "Every requested coarse detail inside the region must refine");
            assertTrue(policy.enabled(world, -64 * stride, -64 * stride, stride));
            assertFalse(policy.enabled(world, 4096, 0, stride), "Exclusive boundary must not authorize an adjacent tile");
            assertFalse(policy.enabled(world, -4096 - 64 * stride, 0, stride));
        }
        assertTrue(policy.enabled(world, 128, 384, 2), "Detail-7 subtiles differ from the detail-10 prewarm key");
        assertFalse(policy.enabled(UUID.randomUUID(), 0, 0, 2), "Authorization is scoped to its world");
        // Registration is independent of jobs/cache entries and remains present until explicitly revoked.
        assertEquals(1, policy.size());
        assertTrue(policy.enabled(world, 128, 384, 2));
    }

    @Test
    void refinementRectangleUnionDoesNotAuthorizeGapsAndUsesExclusiveEdges() {
        UUID world = UUID.randomUUID();
        NekoLodService.RefinementPolicy policy = new NekoLodService.RefinementPolicy(128);
        policy.register(world, 0, 0, 128, 256);
        policy.register(world, 128, 0, 256, 128);
        assertTrue(policy.enabled(world, 0, 128, 2));
        assertTrue(policy.enabled(world, 128, 0, 2));
        assertFalse(policy.enabled(world, 128, 128, 2), "Do not merge an L-shaped union into a broader rectangle");
        assertTrue(policy.enabled(world, 0, 0, 16), "Coarser requested tiles can intersect smaller authorized regions");
        assertFalse(policy.enabled(world, -1024, 0, 16), "Touching the exclusive edge is not intersection");
    }

    @Test
    void sessionRefinementRegistrationIsBoundedAndCanBeRevokedPerWorldOrGlobally() {
        UUID firstWorld = UUID.randomUUID(), otherWorld = UUID.randomUUID();
        NekoLodService.RefinementPolicy policy = new NekoLodService.RefinementPolicy(2);
        policy.register(firstWorld, 0, 0, 128, 128);
        policy.register(firstWorld, 128, 0, 256, 128);
        policy.register(firstWorld, 0, 0, 128, 128); // Re-registering refreshes recency without increasing size.
        policy.register(otherWorld, 256, 0, 384, 128);
        assertEquals(2, policy.size());
        assertTrue(policy.enabled(firstWorld, 0, 0, 2));
        assertFalse(policy.enabled(firstWorld, 128, 0, 2), "Oldest registration must evict at the configured bound");
        policy.clear(firstWorld);
        assertFalse(policy.enabled(firstWorld, 0, 0, 2));
        assertTrue(policy.enabled(otherWorld, 256, 0, 2));
        policy.clear();
        assertEquals(0, policy.size());
        assertFalse(policy.enabled(otherWorld, 256, 0, 2));
    }

    @Test
    void refinementRejectsInvalidCoordinatesStridesAndEmptyPlans() {
        UUID world = UUID.randomUUID();
        NekoLodService.RefinementPolicy policy = new NekoLodService.RefinementPolicy(128);
        assertThrows(IllegalArgumentException.class, () -> policy.register(world, 0, 0, 0, 128));
        assertThrows(IllegalArgumentException.class, () -> policy.register(world, Integer.MIN_VALUE, 0, 128, 128));
        assertThrows(IllegalArgumentException.class, () -> policy.register(world, 0, 0, Integer.MAX_VALUE, 128));
        policy.register(world, 0, 0, 1024, 1024);
        assertFalse(policy.enabled(world, 0, 0, 1));
        assertFalse(policy.enabled(world, 0, 0, 3));
        assertFalse(policy.enabled(world, 1, 0, 2));
        assertFalse(policy.enabled(world, Integer.MAX_VALUE, 0, 16));
        assertThrows(IllegalArgumentException.class, () -> NekoLodCommand.refinementBounds(List.of(), 10));
        var negative = NekoLodCommand.refinementBounds(NekoLodCommand.plan(-1, -1, 64, 10), 10);
        assertEquals(new NekoLodCommand.RefinementBounds(-2048, -2048, 1024, 1024), negative);
    }
}
