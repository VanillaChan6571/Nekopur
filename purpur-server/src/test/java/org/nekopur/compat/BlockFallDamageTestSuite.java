package org.nekopur.compat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.bukkit.support.environment.VanillaFeature;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@NullMarked
@SelectClasses(BlockFallDamageTest.class)
public class BlockFallDamageTestSuite {
}

@VanillaFeature
@NullMarked
class BlockFallDamageTest {
    @Test
    void normalBlockPreservesFallDistance() {
        verifyLanding(Blocks.STONE, 1.0F, 1.0F, 10.0);
    }

    @Test
    void hayRetainsVanillaCushioning() {
        verifyLanding(Blocks.HAY_BLOCK, 1.0F, 1.0F,
            10.0 * (1.0F - Blocks.HAY_BLOCK.getFallDistanceReduction()));
    }

    @Test
    void configuredMultipliersScaleTheFall() {
        verifyLanding(Blocks.STONE, 0.5F, 2.0F, 5.0);
    }

    @Test
    void zeroDistanceMultiplierDisablesFallDamage() {
        verifyLanding(Blocks.STONE, 0.0F, 1.0F, 0.0);
    }

    private void verifyLanding(Block block, float distanceMultiplier, float damageMultiplier, double expectedDistance) {
        float oldDistance = block.fallDistanceMultiplier;
        float oldDamage = block.fallDamageMultiplier;
        try {
            block.fallDistanceMultiplier = distanceMultiplier;
            block.fallDamageMultiplier = damageMultiplier;
            Entity entity = mock(Entity.class, RETURNS_DEEP_STUBS);
            block.fallOn(mock(Level.class), block.defaultBlockState(), BlockPos.ZERO, entity, 10.0);
            verify(entity).causeFallDamage(eq(expectedDistance), eq(damageMultiplier), any());
        } finally {
            block.fallDistanceMultiplier = oldDistance;
            block.fallDamageMultiplier = oldDamage;
        }
    }
}
