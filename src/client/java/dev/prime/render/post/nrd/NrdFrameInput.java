package dev.prime.render.post.nrd;

import dev.prime.render.FrameCamera;
import dev.prime.render.SunDirection;
import java.util.Objects;

/** Complete device-address-free semantic input used to plan one NRD history transition. */
public record NrdFrameInput(
        FrameCamera camera,
        long frameTimeNanos,
        long sceneRevision,
        long textureRevision,
        SunDirection sunDirection,
        float cameraJitterX,
        float cameraJitterY,
        boolean forceRestart,
        NrdDiagnostics.Mode diagnostic) {
    public NrdFrameInput {
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(sunDirection, "sunDirection");
        Objects.requireNonNull(diagnostic, "diagnostic");
        if (!camera.isFinite()) {
            throw new IllegalArgumentException(
                    "NRD camera must be finite");
        }
        if (!Float.isFinite(cameraJitterX)
                || !Float.isFinite(cameraJitterY)
                || Math.abs(cameraJitterX) > 0.5F
                || Math.abs(cameraJitterY) > 0.5F) {
            throw new IllegalArgumentException(
                    "NRD camera jitter must remain inside one source pixel");
        }
    }
}
