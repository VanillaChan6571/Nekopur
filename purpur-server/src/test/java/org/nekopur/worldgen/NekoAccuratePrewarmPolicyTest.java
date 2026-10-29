package org.nekopur.worldgen;

import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NullMarked
final class NekoAccuratePrewarmPolicyTest {
    private static final UUID WORLD = new UUID(0, 1);
    private static final UUID OTHER = new UUID(0, 2);

    @Test
    void accurateFootprintRequiresFinalDensityAtEveryRequestedDetail() {
        NekoLodService.RefinementPolicy policy = new NekoLodService.RefinementPolicy(128);
        policy.register(WORLD, -4096, -4096, 4096, 4096, true);
        for (int stride : new int[] {2, 4, 8, 16}) {
            int width = 64 * stride;
            for (int x = -4096; x < 4096; x += width) {
                for (int z = -4096; z < 4096; z += width) {
                    assertTrue(policy.enabled(WORLD, x, z, stride));
                    assertTrue(policy.accurate(WORLD, x, z, stride));
                    assertFalse(policy.accurate(OTHER, x, z, stride));
                }
            }
            assertFalse(policy.accurate(WORLD, 4096, 0, stride));
            assertFalse(policy.accurate(WORLD, -4096 - width, 0, stride));
        }
        assertFalse(policy.accurate(WORLD, 1, 0, 16));
        assertFalse(policy.accurate(WORLD, 0, 0, 1));
    }

    @Test
    void ordinaryRegistrationCannotDowngradeAccurateRequirementAndRefreshesRecency() {
        NekoLodService.RefinementPolicy policy = new NekoLodService.RefinementPolicy(2);
        policy.register(WORLD, 0, 0, 1024, 1024);
        assertTrue(policy.enabled(WORLD, 0, 0, 16));
        assertFalse(policy.accurate(WORLD, 0, 0, 16));
        policy.register(WORLD, 0, 0, 1024, 1024, true);
        policy.register(OTHER, 0, 0, 1024, 1024);
        policy.register(WORLD, 0, 0, 1024, 1024);
        policy.register(WORLD, 2048, 0, 3072, 1024);
        assertTrue(policy.accurate(WORLD, 0, 0, 16));
        assertFalse(policy.enabled(OTHER, 0, 0, 16));
        assertFalse(policy.accurate(WORLD, 2048, 0, 16));
        assertEquals(2, policy.size());
    }

    @Test
    void overlappingRoughRegionsDoNotExpandAccurateFootprintAndRevocationIsScoped() {
        NekoLodService.RefinementPolicy policy = new NekoLodService.RefinementPolicy(128);
        policy.register(WORLD, 0, 0, 1024, 1024, true);
        policy.register(WORLD, 0, 0, 4096, 4096);
        policy.register(OTHER, 0, 0, 1024, 1024, true);
        assertTrue(policy.accurate(WORLD, 896, 896, 2));
        assertFalse(policy.accurate(WORLD, 1024, 0, 2));
        assertTrue(policy.enabled(WORLD, 1024, 0, 2));
        policy.clear(WORLD);
        assertFalse(policy.accurate(WORLD, 0, 0, 16));
        assertTrue(policy.accurate(OTHER, 0, 0, 16));
        policy.clear();
        assertFalse(policy.accurate(OTHER, 0, 0, 16));
    }

    @Test
    void repeatedAccurateRegistrationRefreshesBoundedRegionRetention() {
        NekoLodService.RefinementPolicy policy = new NekoLodService.RefinementPolicy(2);
        policy.register(WORLD, 0, 0, 1024, 1024, true);
        policy.register(WORLD, 1024, 0, 2048, 1024, true);
        policy.register(WORLD, 0, 0, 1024, 1024, true);
        policy.register(WORLD, 2048, 0, 3072, 1024, true);
        assertTrue(policy.accurate(WORLD, 0, 0, 16));
        assertFalse(policy.accurate(WORLD, 1024, 0, 16));
        assertTrue(policy.accurate(WORLD, 2048, 0, 16));
    }
}
