package org.nekopur.worldgen;

import org.jspecify.annotations.NullMarked;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@NullMarked
@SelectClasses({NekoWorldgenBootstrapTest.class, NekoTerrainTest.class, NekoWorldgenGraphTest.class, NekoSeedPreviewTest.class, NekoSeedViewerTest.class, NekoTallTerrainTest.class, NekoUndergroundStructureTest.class, NekoMonumentPlacementTest.class, NekoForestBiomesTest.class, NekoBiomeClimateTest.class, NekoDistantTerrainTest.class, NekoLodCommandTest.class, org.nekopur.command.NekoPrewarmBatchTest.class, NekoLodWorkerConfigTest.class, NekoAccuratePrewarmPolicyTest.class, NekoNetherWorldgenTest.class})
public class NekoWorldgenTestSuite {
}
