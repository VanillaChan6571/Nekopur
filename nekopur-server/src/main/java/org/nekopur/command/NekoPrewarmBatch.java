package org.nekopur.command;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** A bounded batch whose continuation executor must defer work onto the caller's main thread. */
@NullMarked
final class NekoPrewarmBatch<T, R> {
    private final List<T> items;
    private final int concurrency;
    private final Function<T, CompletableFuture<R>> submit;
    private final Executor continuation;
    private final BiConsumer<T, R> success;
    private final Consumer<Throwable> failure;
    private final Runnable finished;
    private int next;
    private int completed;
    private int inFlight;
    private boolean started;
    private boolean terminal;

    NekoPrewarmBatch(List<T> items, int concurrency, Function<T, CompletableFuture<R>> submit,
                     Executor continuation, BiConsumer<T, R> success, Consumer<Throwable> failure, Runnable finished) {
        if (concurrency < 1) throw new IllegalArgumentException("Prewarm concurrency must be positive");
        this.items = List.copyOf(items);
        this.concurrency = concurrency;
        this.submit = submit;
        this.continuation = continuation;
        this.success = success;
        this.failure = failure;
        this.finished = finished;
    }

    void start() {
        if (started || terminal) return;
        started = true;
        refill();
    }

    void cancel() {
        terminal = true;
        inFlight = 0;
        // Service futures may have other waiters; relinquish callbacks without cancelling them.
    }

    int inFlight() {
        return inFlight;
    }

    int limit() {
        return concurrency;
    }

    private void refill() {
        if (terminal) return;
        if (completed == items.size()) {
            terminal = true;
            finished.run();
            return;
        }
        while (!terminal && inFlight < concurrency && next < items.size()) {
            T item = items.get(next++);
            inFlight++;
            CompletableFuture<R> future;
            try {
                future = java.util.Objects.requireNonNull(submit.apply(item), "Prewarm submission returned no future");
            } catch (Throwable error) {
                fail(error);
                return;
            }
            future.whenComplete((result, error) -> continuation.execute(() -> complete(item, result, error)));
        }
    }

    private void complete(T item, @Nullable R result, @Nullable Throwable error) {
        if (terminal) return;
        inFlight--;
        if (error != null) {
            fail(error);
            return;
        }
        try {
            success.accept(item, result);
        } catch (Throwable callbackError) {
            fail(callbackError);
            return;
        }
        completed++;
        refill();
    }

    private void fail(Throwable error) {
        if (terminal) return;
        terminal = true;
        inFlight = 0;
        failure.accept(error);
    }
}
