package org.nekopur.compat;

import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.bukkit.support.environment.VanillaFeature;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@NullMarked
@SelectClasses(CushionFluidTest.class)
public class CushionFluidTestSuite {}

@VanillaFeature
@NullMarked
class CushionFluidTest {
    @Test
    void waterContactWorksOnFirstAndSubsequentChecks() throws Exception {
        ServerLevel level = mock(ServerLevel.class);
        Cushion cushion = mock(Cushion.class, invocation ->
            invocation.getMethod().getName().equals("tickAtCheckInterval")
                ? invocation.callRealMethod() : RETURNS_DEFAULTS.answer(invocation));
        InsideBlockEffectApplier.StepBasedCollector collector = spy(new InsideBlockEffectApplier.StepBasedCollector());
        Field field = Entity.class.getDeclaredField("insideEffectCollector");
        field.setAccessible(true);
        field.set(cushion, collector);
        when(cushion.level()).thenReturn(level);
        when(cushion.isAlive()).thenReturn(true);
        when(cushion.position()).thenReturn(Vec3.ZERO);
        when(cushion.collidedWithFluid(any(), any(), any(), any())).thenReturn(true);
        when(level.getBlockState(any())).thenReturn(Blocks.WATER.defaultBlockState());
        Method tick = Cushion.class.getDeclaredMethod("tickAtCheckInterval");
        tick.setAccessible(true);

        BlockPos first = new BlockPos(2434, 89, -20616);
        when(cushion.blockPosition()).thenReturn(first);
        tick.invoke(cushion);
        verify(cushion).clearFire();
        verify(collector).advanceStep(0, first);

        BlockPos second = first.above();
        when(cushion.blockPosition()).thenReturn(second);
        tick.invoke(cushion);
        verify(cushion, times(2)).clearFire();
        verify(collector).advanceStep(0, second);
    }
}
