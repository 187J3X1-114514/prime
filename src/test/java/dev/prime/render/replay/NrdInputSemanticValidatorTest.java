package dev.prime.render.replay;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.FrameCamera;
import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.LightingSettings;
import dev.prime.render.MaterialSettings;
import dev.prime.render.SunDirection;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.terrain.TerrainScene;
import java.util.List;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class NrdInputSemanticValidatorTest {
    @Test
    void acceptsCanonicalClearedRestartFrame() {
        RenderReplayCapture capture = capture(false, false);

        assertTrue(NrdInputSemanticValidator.validate(capture).valid());
    }

    @Test
    void reportsNonFiniteInputAndRestartMotion() {
        RenderReplayCapture capture = capture(true, true);
        NrdInputSemanticValidator.Report report =
                NrdInputSemanticValidator.validate(capture);

        assertFalse(report.valid());
        assertTrue(report.violations().stream().anyMatch(
                violation -> violation.reason().equals("non-finite")));
        assertTrue(report.violations().stream().anyMatch(
                violation -> violation.reason().contains("non-zero motion")));
    }

    @Test
    void validatesExplicitHistoryTransitionAndFrameIndex() {
        RenderReplayCapture first = capture(false, false);
        RenderReplayCapture second = withPreparation(
                first,
                preparation(first, 1, false));

        assertTrue(NrdInputSemanticValidator.validate(
                new RenderReplaySequence(List.of(first, second))).valid());

        RenderReplayCapture skipped = withPreparation(
                first,
                preparation(first, 3, false));
        NrdInputSemanticValidator.SequenceReport skippedReport =
                NrdInputSemanticValidator.validate(
                        new RenderReplaySequence(List.of(first, skipped)));
        assertFalse(skippedReport.valid());
        assertTrue(skippedReport.temporalViolations().stream().anyMatch(
                violation -> violation.reason().contains("exactly once")));
    }

    @Test
    void standaloneSequenceRequiresRestartedHistory() {
        RenderReplayCapture first = capture(false, false);
        RenderReplayCapture nonRestart = withPreparation(
                first,
                preparation(first, 1, false));

        NrdInputSemanticValidator.SequenceReport report =
                NrdInputSemanticValidator.validate(
                        new RenderReplaySequence(List.of(nonRestart)));

        assertFalse(report.valid());
        assertTrue(report.temporalViolations().stream().anyMatch(
                violation -> violation.reason().contains("history reset")));
    }

    static RenderReplayCapture capture(
            boolean nonFinite, boolean motion) {
        int[] raw = new int[
                RenderStageSchema.RAW_WAVEFRONT.signalCount() * 4];
        set(raw, RenderStageSchema.RAW_WAVEFRONT, "primary.material", 3, -1.0F);
        set(raw, RenderStageSchema.RAW_WAVEFRONT, "reflection.material", 3, -1.0F);
        if (nonFinite) {
            set(raw, RenderStageSchema.RAW_WAVEFRONT, "primary.diffuse", 0, Float.NaN);
        }
        int[] prepared = new int[
                RenderStageSchema.PREPARED_NRD.signalCount() * 4];
        set(prepared, RenderStageSchema.PREPARED_NRD, "primary.view_z", 0, 65_504.0F);
        set(prepared, RenderStageSchema.PREPARED_NRD, "reflection.view_z", 0, 65_504.0F);
        set(prepared, RenderStageSchema.PREPARED_NRD, "sun.penumbra", 0, 0.0F);
        if (motion) {
            set(prepared, RenderStageSchema.PREPARED_NRD, "primary.motion", 0, 1.0F);
        }
        FrameCamera camera = new FrameCamera(
                new Matrix4f(),
                new Matrix4f(),
                new Matrix4f(),
                0.0,
                64.0,
                0.0,
                0.0,
                64.0,
                0.0);
        SunDirection sun = new SunDirection(0.0F, 1.0F, 0.0F);
        TerrainScene.ResidentSceneView scene =
                new TerrainScene.ResidentSceneView(
                        1L, 2L, 0, 0, 0, 1L, 1L, 1L);
        IntegratorFrameInput frame = new IntegratorFrameInput(
                camera,
                1,
                1,
                sun,
                Short.toUnsignedInt(Float.floatToFloat16(1.0F)),
                0,
                1,
                0,
                false,
                PostProcessingMode.NRD_FSR,
                new LightingSettings.Snapshot(
                        0, 0, 0, 1.0F, 1.0F, 1.0F, 1L),
                new MaterialSettings.Snapshot(90, 0.9F, 1L),
                true,
                false,
                false);
        return new RenderReplayCapture(
                platform(),
                binary(),
                RayTraceReplayInput.capture(frame, scene),
                NrdPreparationReplayInput.capture(
                        camera,
                        camera,
                        0L,
                        scene.temporalRevision(),
                        1L,
                        0.0F,
                        0.0F,
                        0.0F,
                        0.0F,
                        0,
                        true,
                        true,
                        1000.0F / 60.0F,
                        sun,
                        0,
                        false),
                new CapturedRenderStage(
                        RenderStageSchema.RAW_WAVEFRONT, 1, 1, raw),
                new CapturedRenderStage(
                        RenderStageSchema.PREPARED_NRD, 1, 1, prepared));
    }

    private static void set(
            int[] words,
            RenderStageSchema schema,
            String signal,
            int channel,
            float value) {
        words[schema.signalIndex(signal) * 4 + channel] =
                Float.floatToRawIntBits(value);
    }

    private static NrdPreparationReplayInput preparation(
            RenderReplayCapture source, int frameIndex, boolean restart) {
        NrdPreparationReplayInput input = source.nrdPreparation();
        return new NrdPreparationReplayInput(
                input.currentCamera(),
                input.historyCamera(),
                input.frameTimeNanos(),
                input.sceneRevision(),
                input.textureRevision(),
                input.currentJitterX(),
                input.currentJitterY(),
                input.historyJitterX(),
                input.historyJitterY(),
                frameIndex,
                input.forceRestart(),
                restart,
                input.deltaMilliseconds(),
                input.sunDirection(),
                input.diagnosticMode(),
                input.nativeValidation());
    }

    private static RenderReplayCapture withPreparation(
            RenderReplayCapture source,
            NrdPreparationReplayInput preparation) {
        return new RenderReplayCapture(
                source.platform(),
                source.binary(),
                source.frame(),
                preparation,
                source.rawWavefront(),
                source.preparedNrd());
    }

    private static RenderPlatformFingerprint platform() {
        return new RenderPlatformFingerprint(
                "GPU",
                1,
                2,
                3,
                4,
                5,
                "00112233445566778899aabbccddeeff",
                32,
                32,
                64,
                64,
                1 << 20,
                1,
                1L,
                1L,
                256,
                true,
                false,
                true,
                4,
                true);
    }

    static RenderBinaryFingerprint binary() {
        return new RenderBinaryFingerprint(
                "0000000000000000000000000000000000000000000000000000000000000000",
                java.util.List.of());
    }
}
