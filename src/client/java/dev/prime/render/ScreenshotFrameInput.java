package dev.prime.render;

import dev.prime.render.post.PostProcessingMode;
import java.util.Objects;

/** Complete device-address-free semantic input captured for one screenshot sample. */
public record ScreenshotFrameInput(
        FrameCamera camera,
        int width,
        int height,
        long sceneRevision,
        long textureRevision,
        SunDirection sunDirection,
        boolean cameraInWater,
        LightingSettings.Snapshot lighting,
        MaterialSettings.Snapshot material,
        long sampleCount,
        DisplaySettings.Snapshot display) {
    public ScreenshotFrameInput {
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(sunDirection, "sunDirection");
        Objects.requireNonNull(lighting, "lighting");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(display, "display");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Screenshot extent must be positive");
        }
        if (sampleCount < 0L) {
            throw new IllegalArgumentException(
                    "Screenshot sample count must be non-negative");
        }
        if (sceneRevision < 0L || textureRevision < 0L) {
            throw new IllegalArgumentException(
                    "Screenshot scene and texture revisions must be non-negative");
        }
    }

    public ScreenshotFramePlan plan() {
        int sampleIndex = (int) (this.sampleCount & 0xffffL);
        int sampleEpoch = (int) (this.sampleCount >>> 16);
        return new ScreenshotFramePlan(
                this,
                new IntegratorFrameInput(
                        this.camera,
                        this.width,
                        this.height,
                        this.sunDirection,
                        packRayCone(
                                this.camera.projection().m00(),
                                this.camera.projection().m11(),
                                this.width,
                                this.height),
                        sampleIndex,
                        sampleEpoch,
                        0,
                        this.cameraInWater,
                        PostProcessingMode.DISABLED,
                        this.lighting,
                        this.material,
                        false,
                        false,
                        false));
    }

    private static int packRayCone(
            float projectionM00,
            float projectionM11,
            int width,
            int height) {
        float x = 2.0F / (width * Math.abs(projectionM00));
        float y = 2.0F / (height * Math.abs(projectionM11));
        float spread = Math.max(x, y);
        if (!Float.isFinite(spread) || spread <= 0.0F) {
            throw new IllegalArgumentException(
                    "Screenshot ray-cone projection must be finite and non-zero");
        }
        // Native screenshot accumulation has no upscaler-specific LOD bias.
        return Float.floatToFloat16(spread) & 0xffff;
    }
}
