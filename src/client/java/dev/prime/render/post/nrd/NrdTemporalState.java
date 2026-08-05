package dev.prime.render.post.nrd;

import dev.prime.render.FrameCamera;
import dev.prime.render.FrameTime;
import java.util.Objects;

/** Pure NRD history transition; the caller commits {@link Plan#committedState()} after submission. */
final class NrdTemporalState {
    private final FrameCamera camera;
    private final float cameraJitterX;
    private final float cameraJitterY;
    private final int nextFrameIndex;
    private final long frameTimeNanos;

    private NrdTemporalState(
            FrameCamera camera,
            float cameraJitterX,
            float cameraJitterY,
            int nextFrameIndex,
            long frameTimeNanos) {
        this.camera = camera;
        this.cameraJitterX = cameraJitterX;
        this.cameraJitterY = cameraJitterY;
        this.nextFrameIndex = nextFrameIndex;
        this.frameTimeNanos = frameTimeNanos;
    }

    static NrdTemporalState initial() {
        return new NrdTemporalState(
                null, 0.0F, 0.0F, 0, 0L);
    }

    Plan plan(NrdFrameInput input) {
        Objects.requireNonNull(input, "input");
        boolean initialized = this.camera != null;
        boolean restart = input.forceRestart()
                || !initialized;
        FrameCamera historyCamera = restart ? input.camera() : this.camera;
        float historyJitterX = restart ? input.cameraJitterX() : this.cameraJitterX;
        float historyJitterY = restart ? input.cameraJitterY() : this.cameraJitterY;
        int currentFrameIndex = restart ? 0 : this.nextFrameIndex;
        float deltaMilliseconds = FrameTime.deltaMilliseconds(
                initialized, input.frameTimeNanos(), this.frameTimeNanos);
        NrdTemporalState committed = new NrdTemporalState(
                input.camera(),
                input.cameraJitterX(),
                input.cameraJitterY(),
                currentFrameIndex + 1,
                input.frameTimeNanos());
        return new Plan(
                historyCamera,
                historyJitterX,
                historyJitterY,
                currentFrameIndex,
                restart,
                deltaMilliseconds,
                committed);
    }

    record Plan(
            FrameCamera historyCamera,
            float historyJitterX,
            float historyJitterY,
            int currentFrameIndex,
            boolean restart,
            float deltaMilliseconds,
            NrdTemporalState committedState) {
        Plan {
            Objects.requireNonNull(historyCamera, "historyCamera");
            Objects.requireNonNull(committedState, "committedState");
        }

        NrdFramePlan semanticPlan(NrdFrameInput input) {
            return new NrdFramePlan(
                    input,
                    this.historyCamera,
                    this.historyJitterX,
                    this.historyJitterY,
                    this.currentFrameIndex,
                    this.restart,
                    this.deltaMilliseconds);
        }
    }
}
