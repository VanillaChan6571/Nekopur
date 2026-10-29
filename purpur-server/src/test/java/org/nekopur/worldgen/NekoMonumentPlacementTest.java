package org.nekopur.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentPieces;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentStructure;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.VanillaFeature;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.nekopur.worldgen.preview.NekoSeedPreview;

@VanillaFeature
@NullMarked
class NekoMonumentPlacementTest {
    @Test
    void tallMonumentFollowsDeepSeabedButStandardKeepsVanillaHeight() {
        var registries = RegistryHelper.registryAccess();
        NoiseBasedChunkGenerator tall = NekoSeedPreview.generator(registries, "neko-mmo-tall");
        RandomState random = RandomState.create(registries.lookupOrThrow(Registries.NOISE), 6571L, tall.generatorSettings().value());
        int base = NekoMonumentPlacement.baseY(tall, LevelHeightAccessor.create(-128, 640), random, -12800, -31232);
        assertTrue(base < -64, "Monument must descend to the deep ocean floor: " + base);
        assertTrue(base >= -120);
        assertTrue(base + 22 < tall.getSeaLevel());
        NoiseBasedChunkGenerator standard = NekoSeedPreview.generator(registries, "neko");
        assertEquals(39, NekoMonumentPlacement.baseY(standard, LevelHeightAccessor.create(-64, 384), random, -12800, -31232));
        System.out.println("Tall deep-ocean monument base Y=" + base);
    }

    @Test
    void monumentReloadRetainsHeightAndMovesEveryRoomTogether() throws Exception {
        for (Direction orientation : Direction.Plane.HORIZONTAL) {
            var original = new OceanMonumentPieces.MonumentBuilding(RandomSource.create(123), -100, -80, 200, orientation);
            var regenerated = OceanMonumentStructure.regeneratePiecesAfterLoad(new ChunkPos(0, 0), 123,
                new PiecesContainer(java.util.List.of(original))).pieces().get(0);
            assertEquals(-80, regenerated.getBoundingBox().minY());
            assertEquals(original.getBoundingBox(), regenerated.getBoundingBox());
            // Child rooms are not independent saved pieces. Verify reconstruction
            // gives every room the same vertical translation as the outer shell.
            var vanilla = new OceanMonumentPieces.MonumentBuilding(RandomSource.create(123), -100, 200, orientation);
            var childrenField = OceanMonumentPieces.MonumentBuilding.class.getDeclaredField("childPieces");
            childrenField.setAccessible(true);
            var shiftedRooms = (java.util.List<?>) childrenField.get(original);
            var vanillaRooms = (java.util.List<?>) childrenField.get(vanilla);
            assertEquals(vanillaRooms.size(), shiftedRooms.size());
            for (int i = 0; i < vanillaRooms.size(); i++) {
                var shifted = ((net.minecraft.world.level.levelgen.structure.StructurePiece) shiftedRooms.get(i)).getBoundingBox();
                var baseline = ((net.minecraft.world.level.levelgen.structure.StructurePiece) vanillaRooms.get(i)).getBoundingBox();
                assertEquals(-119, shifted.minY() - baseline.minY());
                assertEquals(-119, shifted.maxY() - baseline.maxY());
                assertEquals(baseline.minX(), shifted.minX());
                assertEquals(baseline.minZ(), shifted.minZ());
            }
        }
    }
}
