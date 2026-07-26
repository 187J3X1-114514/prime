package dev.prime.render.vulkan.replay;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Lock-free lifecycle state for one optional replay request. */
final class ReplayProbeRequestState<T> {
    private final AtomicReference<Request<T>> pending =
            new AtomicReference<>();
    private final AtomicBoolean destroyed = new AtomicBoolean();

    CompletableFuture<T> request(
            int width, int height) {
        validateExtent(width, height);
        if (this.destroyed.get()) {
            throw new IllegalStateException(
                    "Replay probe controller is destroyed");
        }
        Request<T> request = new Request<>(width, height);
        if (!this.pending.compareAndSet(null, request)) {
            throw new IllegalStateException(
                    "A replay probe is already pending");
        }
        if (this.destroyed.get()
                && this.pending.compareAndSet(request, null)) {
            request.result.completeExceptionally(closedFailure());
        }
        return request.result;
    }

    Request<T> claim() {
        Request<T> request = this.pending.get();
        return request != null
                        && request.started.compareAndSet(false, true)
                ? request
                : null;
    }

    void complete(
            Request<T> request, T result) {
        Objects.requireNonNull(result, "result");
        retire(request);
        request.result.complete(result);
    }

    void fail(Request<T> request, Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        retire(request);
        request.result.completeExceptionally(failure);
    }

    void destroy() {
        if (!this.destroyed.compareAndSet(false, true)) {
            return;
        }
        Request<T> request = this.pending.getAndSet(null);
        if (request != null) {
            request.result.completeExceptionally(closedFailure());
        }
    }

    private void retire(Request<T> request) {
        Objects.requireNonNull(request, "request");
        this.pending.compareAndSet(request, null);
    }

    private static void validateExtent(int width, int height) {
        if (width <= 0
                || height <= 0
                || Math.multiplyExact((long) width, height) > 256L * 256L) {
            throw new IllegalArgumentException(
                    "Replay probe must be positive and no larger than 65,536 pixels");
        }
    }

    private static IllegalStateException closedFailure() {
        return new IllegalStateException(
                "Renderer closed before replay capture completed");
    }

    static final class Request<T> {
        private final int width;
        private final int height;
        private final AtomicBoolean started = new AtomicBoolean();
        private final CompletableFuture<T> result =
                new CompletableFuture<>();

        private Request(int width, int height) {
            this.width = width;
            this.height = height;
        }

        int width() {
            return this.width;
        }

        int height() {
            return this.height;
        }
    }
}
