package org.nekopur.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

@NullMarked
public class NekoLodWorkerConfigTest {
    @TempDir Path directory;

    @AfterEach
    void shutdownPools() {
        NekoLodService.shutdown();
    }

    @Test
    void accurateWorkersFollowExistingWorkersAndReloadBothDirections() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("nekopur.lod-preview.workers", 4);
            configure(yaml);
            assertWorkers(4, 4);
            yaml.set("nekopur.lod-preview.workers", 7);
            configure(yaml);
            assertWorkers(7, 7);
            yaml.set("nekopur.lod-preview.workers", 2);
            configure(yaml);
            assertWorkers(2, 2);
        }
    }

    @Test
    void explicitAccurateOverrideAndClampsRemainIndependent() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("nekopur.lod-preview.workers", 4);
            yaml.set("nekopur.lod-preview.upgrade-workers", 1);
            configure(yaml);
            assertWorkers(4, 1);
            yaml.set("nekopur.lod-preview.workers", -10);
            yaml.set("nekopur.lod-preview.upgrade-workers", 99);
            configure(yaml);
            assertWorkers(1, 8);
            yaml.set("nekopur.lod-preview.workers", 99);
            yaml.set("nekopur.lod-preview.upgrade-workers", -10);
            configure(yaml);
            assertWorkers(8, 1);
        }
    }

    @Test
    void accuratePoolActuallyRunsMultipleTasksAtOnce() throws Exception {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("nekopur.lod-preview.workers", 3);
            configure(yaml);
            Field field = NekoLodService.class.getDeclaredField("upgradeExecutor");
            field.setAccessible(true);
            ThreadPoolExecutor pool = (ThreadPoolExecutor) field.get(null);
            assertNotNull(pool);
            CountDownLatch started = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(2);
            Runnable blocked = () -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            };
            pool.execute(blocked);
            pool.execute(blocked);
            try {
                assertTrue(started.await(10, TimeUnit.SECONDS), "Two tasks must start before either is released");
                assertTrue(pool.getActiveCount() >= 2);
                assertTrue(NekoLodService.status().contains(" upgrade-active=2"));
            } finally {
                release.countDown();
            }
            assertTrue(finished.await(10, TimeUnit.SECONDS));
        }
    }

    private void configure(YamlConfiguration yaml) {
        NekoLodService.configure(yaml, directory.resolve("purpur.yml").toFile());
    }

    private static void assertWorkers(int preview, int accurate) {
        assertEquals(preview, NekoLodService.prewarmConcurrency(false));
        assertEquals(accurate, NekoLodService.prewarmConcurrency(true));
        String status = NekoLodService.status();
        assertTrue(status.contains(" workers=" + preview + " "), status);
        assertTrue(status.contains(" upgrade-workers=" + accurate + " "), status);
    }
}
