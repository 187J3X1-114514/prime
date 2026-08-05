package dev.prime.render.replay;

import dev.prime.render.FrameCamera;
import dev.prime.render.FrameTime;
import dev.prime.render.SunDirection;
import dev.prime.render.post.nrd.NrdFramePlan;
import java.util.Objects;

/** Complete planned temporal input used by Prime's raygen-to-NRD adapter. */
public record NrdPreparationReplayInput(
        FrameCameraSnapshot currentCamera,
        FrameCameraSnapshot historyCamera,
        long frameTimeNanos,
        long sceneRevision,
        long textureRevision,
        float currentJitterX,
        float currentJitterY,
        float historyJitterX,
        float historyJitterY,
        int frameIndex,
        boolean forceRestart,
        boolean restart,
        float deltaMilliseconds,
        SunDirection sunDirection,
        int diagnosticMode,
        boolean nativeValidation) {
    public NrdPreparationReplayInput {
        Objects.requireNonNull(currentCamera, "currentCamera");
        Objects.requireNonNull(historyCamera, "historyCamera");
        Objects.requireNonNull(sunDirection, "sunDirection");
        if (!currentCamera.isFinite() || !historyCamera.isFinite()) {
            throw new IllegalArgumentException(
                    "NRD replay cameras must be finite");
        }
        if (!Float.isFinite(currentJitterX)
                || !Float.isFinite(currentJitterY)
                || !Float.isFinite(historyJitterX)
                || !Float.isFinite(historyJitterY)
                || Math.abs(currentJitterX) > 0.5F
                || Math.abs(currentJitterY) > 0.5F
                || Math.abs(historyJitterX) > 0.5F
                || Math.abs(historyJitterY) > 0.5F
                || !Float.isFinite(deltaMilliseconds)
                || deltaMilliseconds < 0.0F
                || deltaMilliseconds
                        > FrameTime.MAXIMUM_DELTA_MILLISECONDS) {
            throw new IllegalArgumentException(
                    "NRD replay temporal values must be finite and non-negative where required");
        }
        if (frameIndex < 0) {
            throw new IllegalArgumentException(
                    "NRD replay frame index must be non-negative");
        }
        if (diagnosticMode < 0
                || diagnosticMode
                        > dev.prime.render.post.nrd.NrdDiagnostics
                                .MAX_OUTPUT_SELECTOR) {
            throw new IllegalArgumentException(
                    "NRD replay diagnostic selector is invalid");
        }
    }

    public static NrdPreparationReplayInput capture(
            NrdFramePlan plan) {
        Objects.requireNonNull(plan, "plan");
        return capture(
                plan.input().camera(),
                plan.historyCamera(),
                plan.input().frameTimeNanos(),
                plan.input().sceneRevision(),
                plan.input().textureRevision(),
                plan.input().cameraJitterX(),
                plan.input().cameraJitterY(),
                plan.historyJitterX(),
                plan.historyJitterY(),
                plan.frameIndex(),
                plan.input().forceRestart(),
                plan.restart(),
                plan.deltaMilliseconds(),
                plan.input().sunDirection(),
                plan.input().diagnostic().outputSelector(),
                plan.input().diagnostic().nativeValidation());
    }

    public static NrdPreparationReplayInput capture(
            FrameCamera currentCamera,
            FrameCamera historyCamera,
            long frameTimeNanos,
            long sceneRevision,
            long textureRevision,
            float currentJitterX,
            float currentJitterY,
            float historyJitterX,
            float historyJitterY,
            int frameIndex,
            boolean forceRestart,
            boolean restart,
            float deltaMilliseconds,
            SunDirection sunDirection,
            int diagnosticMode,
            boolean nativeValidation) {
        return new NrdPreparationReplayInput(
                FrameCameraSnapshot.capture(currentCamera),
                FrameCameraSnapshot.capture(historyCamera),
                frameTimeNanos,
                sceneRevision,
                textureRevision,
                currentJitterX,
                currentJitterY,
                historyJitterX,
                historyJitterY,
                frameIndex,
                forceRestart,
                restart,
                deltaMilliseconds,
                sunDirection,
                diagnosticMode,
                nativeValidation);
    }
}
