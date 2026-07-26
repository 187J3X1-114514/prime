package dev.prime.render.vulkan.nrd;

import dev.prime.render.FrameCamera;
import dev.prime.render.FrameTime;
import dev.prime.render.SunDirection;
import java.util.Objects;

/** Pure NRD history transition; the caller commits {@link Plan#committedState()} after submission. */
final class NrdTemporalState {
    private static final float SUN_DISCONTINUITY_COSINE =
            (float) Math.cos(Math.toRadians(1.0));
    private final FrameCamera camera;
    private final long sceneRevision;
    private final long textureRevision;
    private final SunDirection sunDirection;
    private final float cameraJitterX;
    private final float cameraJitterY;
    private final int nextFrameIndex;
    private final long frameTimeNanos;

    private NrdTemporalState(
            FrameCamera camera,
            long sceneRevision,
            long textureRevision,
            SunDirection sunDirection,
            float cameraJitterX,
            float cameraJitterY,
            int nextFrameIndex,
            long frameTimeNanos) {
        this.camera = camera;
        this.sceneRevision = sceneRevision;
        this.textureRevision = textureRevision;
        this.sunDirection = sunDirection;
        this.cameraJitterX = cameraJitterX;
        this.cameraJitterY = cameraJitterY;
        this.nextFrameIndex = nextFrameIndex;
        this.frameTimeNanos = frameTimeNanos;
    }

    static NrdTemporalState initial() {
        return new NrdTemporalState(
                null, Long.MIN_VALUE, Long.MIN_VALUE, null, 0.0F, 0.0F, 0, 0L);
    }

    Plan plan(NrdFrameInput input) {
        Objects.requireNonNull(input, "input");
        boolean initialized = this.camera != null;
        boolean restart = input.forceRestart()
                || !initialized
                || input.sceneRevision() != this.sceneRevision
                || input.textureRevision() != this.textureRevision
                || sunDirectionDiscontinuous(input.sunDirection(), this.sunDirection);
        FrameCamera historyCamera = restart ? input.camera() : this.camera;
        float historyJitterX = restart ? input.cameraJitterX() : this.cameraJitterX;
        float historyJitterY = restart ? input.cameraJitterY() : this.cameraJitterY;
        int currentFrameIndex = restart ? 0 : this.nextFrameIndex;
        float deltaMilliseconds = FrameTime.deltaMilliseconds(
                initialized, input.frameTimeNanos(), this.frameTimeNanos);
        NrdTemporalState committed = new NrdTemporalState(
                input.camera(),
                input.sceneRevision(),
                input.textureRevision(),
                input.sunDirection(),
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

    private static boolean sunDirectionDiscontinuous(
            SunDirection current,
            SunDirection previous) {
        if (previous == null) {
            return true;
        }
        float cosine = current.x() * previous.x()
                + current.y() * previous.y()
                + current.z() * previous.z();
        // Normal day progression remains continuous; command-driven time jumps restart history.
        return cosine < SUN_DISCONTINUITY_COSINE;
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
