package dev.prime.render.vulkan.nrd;

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
        if (!Float.isFinite(cameraJitterX) || !Float.isFinite(cameraJitterY)) {
            throw new IllegalArgumentException("NRD camera jitter must be finite");
        }
    }
}
