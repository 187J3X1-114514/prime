package dev.prime.render.vulkan.dlss;

import dev.prime.render.fsr.FsrSettings;
import java.nio.ByteBuffer;
import org.joml.Matrix4fc;

/** Exact std430 layout of {@code rr_prepare.comp}'s push constants. */
final class DlssRrPrepareConstants {
    static final int CURRENT_CLIP_TO_WORLD = 0;
    static final int PREVIOUS_WORLD_TO_CLIP = 64;
    static final int VIEW_ROTATION = 128;
    static final int SUN_RADIANCE = 192;
    static final int JITTER_X = 200;
    static final int JITTER_Y = 204;
    static final int SIZE = 208;

    private DlssRrPrepareConstants() {}

    static void write(
            ByteBuffer target,
            Matrix4fc currentClipToWorld,
            Matrix4fc previousWorldToClip,
            Matrix4fc viewRotation,
            float sunRadiance,
            FsrSettings.Jitter jitter) {
        currentClipToWorld.get(CURRENT_CLIP_TO_WORLD, target);
        previousWorldToClip.get(PREVIOUS_WORLD_TO_CLIP, target);
        viewRotation.get(VIEW_ROTATION, target);
        target.putFloat(SUN_RADIANCE, sunRadiance);
        target.putInt(196, 0);
        target.putFloat(JITTER_X, jitter.x());
        target.putFloat(JITTER_Y, jitter.y());
    }
}
