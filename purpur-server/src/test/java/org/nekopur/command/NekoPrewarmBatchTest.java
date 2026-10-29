package org.nekopur.command;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

@NullMarked
public class NekoPrewarmBatchTest {
    @Test
    void reservesBoundedUniqueSlotsAndFinishesAfterOutOfOrderCompletions() {
        DeferredExecutor continuation = new DeferredExecutor();
        List<Integer> submitted = new ArrayList<>();
        List<Integer> completed = new ArrayList<>();
        Map<Integer, CompletableFuture<String>> futures = new HashMap<>();
        AtomicInteger finished = new AtomicInteger();
        NekoPrewarmBatch<Integer, String> batch = new NekoPrewarmBatch<>(List.of(0, 1, 2, 3, 4), 2,
            item -> {
                submitted.add(item);
                CompletableFuture<String> future = new CompletableFuture<>();
                futures.put(item, future);
                return future;
            }, continuation, (item, result) -> completed.add(item), error -> fail(error), finished::incrementAndGet);
        batch.start();
        batch.start();
        assertEquals(List.of(0, 1), submitted);
        assertEquals(2, batch.inFlight());
        assertEquals(2, batch.limit());
        futures.get(1).complete("one");
        assertEquals(List.of(0, 1), submitted);
        continuation.runOne();
        assertEquals(List.of(0, 1, 2), submitted);
        assertEquals(List.of(1), completed);
        assertEquals(2, batch.inFlight());
        futures.get(2).complete("two");
        continuation.runOne();
        futures.get(3).complete("three");
        continuation.runOne();
        futures.get(4).complete("four");
        continuation.runOne();
        assertEquals(List.of(0, 1, 2, 3, 4), submitted);
        assertEquals(0, finished.get(), "The remaining first tile must finish before phase transition");
        assertEquals(1, batch.inFlight());
        futures.get(0).complete("zero");
        continuation.runOne();
        assertEquals(1, finished.get());
        assertEquals(0, batch.inFlight());
        batch.start();
        assertEquals(1, finished.get());
    }

    @Test
    void immediateCacheHitsAlwaysContinueThroughDeferredExecutor() {
        DeferredExecutor continuation = new DeferredExecutor();
        AtomicInteger submits = new AtomicInteger();
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        List<Integer> items = java.util.stream.IntStream.range(0, 10000).boxed().toList();
        NekoPrewarmBatch<Integer, String> batch = new NekoPrewarmBatch<>(items, 3,
            item -> { submits.incrementAndGet(); return CompletableFuture.completedFuture("cached"); }, continuation,
            (item, result) -> successes.incrementAndGet(), error -> fail(error), finished::incrementAndGet);
        batch.start();
        assertEquals(3, submits.get());
        assertEquals(0, successes.get());
        assertEquals(3, continuation.tasks.size());
        while (!continuation.tasks.isEmpty()) {
            continuation.runOne();
            assertTrue(batch.inFlight() <= 3);
            assertTrue(continuation.tasks.size() <= 3);
        }
        assertEquals(items.size(), submits.get());
        assertEquals(items.size(), successes.get());
        assertEquals(1, finished.get());
    }

    @Test
    void cancelIgnoresAlreadyQueuedAndLateCallbacksWithoutCancellingSharedFutures() {
        DeferredExecutor continuation = new DeferredExecutor();
        List<CompletableFuture<String>> futures = new ArrayList<>();
        AtomicInteger callbacks = new AtomicInteger();
        NekoPrewarmBatch<Integer, String> batch = new NekoPrewarmBatch<>(List.of(0, 1, 2), 2,
            item -> { CompletableFuture<String> future = new CompletableFuture<>(); futures.add(future); return future; },
            continuation, (item, result) -> callbacks.incrementAndGet(), error -> callbacks.incrementAndGet(), callbacks::incrementAndGet);
        batch.start();
        futures.get(0).complete("queued");
        batch.cancel();
        assertFalse(futures.get(1).isCancelled());
        futures.get(1).completeExceptionally(new IllegalStateException("late"));
        while (!continuation.tasks.isEmpty()) continuation.runOne();
        assertEquals(0, callbacks.get());
        assertEquals(2, futures.size());
        assertEquals(0, batch.inFlight());
        batch.start();
        assertEquals(2, futures.size());
    }

    @Test
    void asynchronousFailureStopsRefillsAndTerminatesOnlyOnce() {
        DeferredExecutor continuation = new DeferredExecutor();
        List<CompletableFuture<String>> futures = new ArrayList<>();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        NekoPrewarmBatch<Integer, String> batch = new NekoPrewarmBatch<>(List.of(0, 1, 2), 2,
            item -> { CompletableFuture<String> future = new CompletableFuture<>(); futures.add(future); return future; },
            continuation, (item, result) -> fail("Success after terminal failure"), error -> failed.incrementAndGet(), finished::incrementAndGet);
        batch.start();
        futures.get(0).completeExceptionally(new IllegalStateException("sampling failed"));
        continuation.runOne();
        assertEquals(1, failed.get());
        futures.get(1).completeExceptionally(new IllegalStateException("stale failure"));
        continuation.runOne();
        assertEquals(1, failed.get());
        assertEquals(0, finished.get());
        assertEquals(2, futures.size());
        assertEquals(0, batch.inFlight());
    }

    @Test
    void synchronousSubmitFailureStopsBeforeDispatchingRemainingItems() {
        DeferredExecutor continuation = new DeferredExecutor();
        AtomicInteger submitted = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        IllegalStateException expected = new IllegalStateException("submit failed");
        NekoPrewarmBatch<Integer, String> batch = new NekoPrewarmBatch<>(List.of(0, 1, 2), 3,
            item -> { submitted.incrementAndGet(); throw expected; }, continuation,
            (item, result) -> fail("No success expected"), error -> { assertSame(expected, error); failed.incrementAndGet(); },
            () -> fail("No normal completion expected"));
        batch.start();
        assertEquals(1, submitted.get());
        assertEquals(1, failed.get());
        assertEquals(0, batch.inFlight());
        assertTrue(continuation.tasks.isEmpty());
    }

    private static final class DeferredExecutor implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            tasks.addLast(task);
        }

        void runOne() {
            tasks.removeFirst().run();
        }
    }
}
