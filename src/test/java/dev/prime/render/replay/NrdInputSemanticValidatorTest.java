package dev.prime.render.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.AstronomySettings;
import dev.prime.render.AstronomyState;
import dev.prime.render.FrameCamera;
import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.LightingSettings;
import dev.prime.render.MaterialSettings;
import dev.prime.render.SunDirection;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.TransparentGuideMode;
import dev.prime.render.vulkan.terrain.TerrainScene;
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
    void reportsNonFiniteActiveInput() {
        RenderReplayCapture capture = capture(true, false);
        NrdInputSemanticValidator.Report report =
                NrdInputSemanticValidator.validate(capture);

        assertFalse(report.valid());
        assertTrue(report.violations().stream().anyMatch(
                violation -> violation.signal().equals("primary.diffuse")
                        && violation.reason().equals("non-finite")));
        assertTrue(report.violations().stream().anyMatch(
                violation -> violation.signal().equals("reflection.material")
                        && violation.channel() == 3
                        && violation.reason().equals("non-finite")));
    }

    @Test
    void acceptsInactivePayloadsAndTransportedBitPatterns() {
        RenderReplayCapture source = capture(false, false);
        int[] raw = source.rawWavefront().words();
        int[] prepared = source.preparedNrd().words();
        setStaticPrimarySurface(raw, prepared);
        set(
                raw,
                RenderStageSchema.RAW_WAVEFRONT,
                "primary.position",
                3,
                Float.NaN);
        set(
                raw,
                RenderStageSchema.RAW_WAVEFRONT,
                "reflection.diffuse",
                0,
                Float.NaN);
        set(
                raw,
                RenderStageSchema.RAW_WAVEFRONT,
                "display.position",
                3,
                Float.NaN);
        RenderReplayCapture valid = new RenderReplayCapture(
                source.platform(),
                source.binary(),
                source.frame(),
                source.nrdPreparation(),
                new CapturedRenderStage(
                        RenderStageSchema.RAW_WAVEFRONT,
                        1,
                        1,
                        raw),
                new CapturedRenderStage(
                        RenderStageSchema.PREPARED_NRD,
                        1,
                        1,
                        prepared),
                source.postNrd());

        NrdInputSemanticValidator.Report report =
                NrdInputSemanticValidator.validate(valid);
        assertTrue(report.valid(), report::toString);
    }

    @Test
    void restartDoesNotChangeTheMotionContract() {
        RenderReplayCapture restart = capture(false, true);
        RenderReplayCapture continued = withPreparation(
                restart, preparation(restart, 1, false));

        assertEquals(
                NrdInputSemanticValidator.validate(restart),
                NrdInputSemanticValidator.validate(continued));
    }

    @Test
    void reportsFsrDepthMotionAndUnusedChannelContractViolations() {
        RenderReplayCapture source = capture(false, false);
        int[] prepared = source.preparedNrd().words();
        set(
                prepared,
                RenderStageSchema.PREPARED_NRD,
                "fsr.depth",
                0,
                2.0F);
        set(
                prepared,
                RenderStageSchema.PREPARED_NRD,
                "fsr.motion",
                0,
                0.25F);
        set(
                prepared,
                RenderStageSchema.PREPARED_NRD,
                "fsr.motion",
                2,
                1.0F);
        RenderReplayCapture invalid = new RenderReplayCapture(
                source.platform(),
                source.binary(),
                source.frame(),
                source.nrdPreparation(),
                source.rawWavefront(),
                new CapturedRenderStage(
                        RenderStageSchema.PREPARED_NRD,
                        1,
                        1,
                        prepared),
                source.postNrd());

        NrdInputSemanticValidator.Report report =
                NrdInputSemanticValidator.validate(invalid);

        assertFalse(report.valid());
        assertTrue(report.violations().stream().anyMatch(
                violation -> violation.signal().equals("fsr.depth")
                        && violation.reason().contains(
                                "reversed-infinite")));
        assertTrue(report.violations().stream().anyMatch(
                violation -> violation.signal().equals("fsr.motion")
                        && violation.reason().contains(
                                "current-to-previous")));
        assertTrue(report.violations().stream().anyMatch(
                violation -> violation.signal().equals("fsr.motion")
                        && violation.reason().contains("unused")));
    }

    @Test
    void reportsInvalidPostNrdCompositeAndFsrMasks() {
        RenderReplayCapture source = capture(false, false);
        int[] postNrd = source.postNrd().words();
        set(
                postNrd,
                RenderStageSchema.POST_NRD,
                "composite.color",
                0,
                Float.NaN);
        set(
                postNrd,
                RenderStageSchema.POST_NRD,
                "fsr.reactive",
                0,
                1.25F);
        RenderReplayCapture invalid = new RenderReplayCapture(
                source.platform(),
                source.binary(),
                source.frame(),
                source.nrdPreparation(),
                source.rawWavefront(),
                source.preparedNrd(),
                new CapturedRenderStage(
                        RenderStageSchema.POST_NRD,
                        1,
                        1,
                        postNrd));

        NrdInputSemanticValidator.Report report =
                NrdInputSemanticValidator.validate(invalid);

        assertFalse(report.valid());
        assertTrue(report.violations().stream().anyMatch(
                violation -> violation.stage() == RenderStageSchema.POST_NRD
                        && violation.signal().equals("composite.color")
                        && violation.reason().equals("non-finite")));
        assertTrue(report.violations().stream().anyMatch(
                violation -> violation.stage() == RenderStageSchema.POST_NRD
                        && violation.signal().equals("fsr.reactive")
                        && violation.reason().contains("outside")));
    }

    @Test
    void acceptsSignedYCoCgChromaInPreparedSh0() {
        RenderReplayCapture source = capture(false, false);
        int[] raw = source.rawWavefront().words();
        int[] prepared = source.preparedNrd().words();
        setStaticPrimarySurface(raw, prepared);
        set(
                prepared,
                RenderStageSchema.PREPARED_NRD,
                "primary.diffuse_sh0",
                1,
                -0.25F);
        set(
                prepared,
                RenderStageSchema.PREPARED_NRD,
                "primary.diffuse_sh0",
                2,
                -0.125F);
        RenderReplayCapture signedChroma = new RenderReplayCapture(
                source.platform(),
                source.binary(),
                source.frame(),
                source.nrdPreparation(),
                new CapturedRenderStage(
                        RenderStageSchema.RAW_WAVEFRONT,
                        1,
                        1,
                        raw),
                new CapturedRenderStage(
                        RenderStageSchema.PREPARED_NRD,
                        1,
                        1,
                        prepared),
                source.postNrd());

        NrdInputSemanticValidator.Report report =
                NrdInputSemanticValidator.validate(signedChroma);
        assertTrue(report.valid(), report::toString);
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

    @Test
    void productionHistoryRejectsForgedDeltaAndBackwardsTime() {
        RenderReplayCapture first = capture(false, false);
        NrdPreparationReplayInput valid =
                preparation(first, 1, false);
        RenderReplayCapture wrongDelta = withPreparation(
                first,
                new NrdPreparationReplayInput(
                        valid.currentCamera(),
                        valid.historyCamera(),
                        valid.frameTimeNanos(),
                        valid.sceneRevision(),
                        valid.textureRevision(),
                        valid.currentJitterX(),
                        valid.currentJitterY(),
                        valid.historyJitterX(),
                        valid.historyJitterY(),
                        valid.frameIndex(),
                        valid.forceRestart(),
                        valid.restart(),
                        16.0F,
                        valid.sunDirection(),
                        valid.diagnosticMode(),
                        valid.nativeValidation()));
        NrdInputSemanticValidator.SequenceReport deltaReport =
                NrdInputSemanticValidator.validate(
                        new RenderReplaySequence(List.of(first, wrongDelta)));
        assertFalse(deltaReport.valid());
        assertTrue(deltaReport.temporalViolations().stream().anyMatch(
                violation -> violation.reason().contains("frame delta")));

        RenderReplayCapture backwards = withPreparation(
                first,
                new NrdPreparationReplayInput(
                        valid.currentCamera(),
                        valid.historyCamera(),
                        -1L,
                        valid.sceneRevision(),
                        valid.textureRevision(),
                        valid.currentJitterX(),
                        valid.currentJitterY(),
                        valid.historyJitterX(),
                        valid.historyJitterY(),
                        valid.frameIndex(),
                        valid.forceRestart(),
                        valid.restart(),
                        0.0F,
                        valid.sunDirection(),
                        valid.diagnosticMode(),
                        valid.nativeValidation()));
        NrdInputSemanticValidator.SequenceReport backwardsReport =
                NrdInputSemanticValidator.validate(
                        new RenderReplaySequence(List.of(first, backwards)));
        assertFalse(backwardsReport.valid());
        assertTrue(backwardsReport.temporalViolations().stream().anyMatch(
                violation -> violation.reason().contains(
                        "invalid production temporal input")));
    }

    static RenderReplayCapture capture(
            boolean nonFinite, boolean motion) {
        int[] raw = new int[
                RenderStageSchema.RAW_WAVEFRONT.signalCount() * 4];
        set(raw, RenderStageSchema.RAW_WAVEFRONT, "primary.material", 3, -1.0F);
        set(raw, RenderStageSchema.RAW_WAVEFRONT, "reflection.material", 3, -1.0F);
        if (nonFinite) {
            set(raw, RenderStageSchema.RAW_WAVEFRONT, "primary.material", 3, 1.0F);
            set(raw, RenderStageSchema.RAW_WAVEFRONT, "primary.diffuse", 0, Float.NaN);
            set(
                    raw,
                    RenderStageSchema.RAW_WAVEFRONT,
                    "reflection.material",
                    3,
                    Float.NaN);
        }
        int[] prepared = new int[
                RenderStageSchema.PREPARED_NRD.signalCount() * 4];
        int[] postNrd = new int[
                RenderStageSchema.POST_NRD.signalCount() * 4];
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
        AstronomyState astronomy = AstronomyState.atSolarHourAngle(
                0.0F, AstronomySettings.defaults());
        SunDirection sun = astronomy.sunDirection();
        TerrainScene.ResidentSceneView scene =
                new TerrainScene.ResidentSceneView(
                        1L, 2L, 0, 0, 0, 1L, 1L, 1L);
        IntegratorFrameInput frame = new IntegratorFrameInput(
                camera,
                1,
                1,
                astronomy,
                Short.toUnsignedInt(Float.floatToFloat16(1.0F)),
                0,
                1,
                0,
                false,
                PostProcessingMode.NRD_FSR,
                TransparentGuideMode.REFLECTION_AND_TRANSMISSION,
                new LightingSettings.Snapshot(
                        0, 0, 0, 1L),
                new MaterialSettings.Snapshot(90, 1L),
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
                        RenderStageSchema.PREPARED_NRD, 1, 1, prepared),
                new CapturedRenderStage(
                        RenderStageSchema.POST_NRD, 1, 1, postNrd));
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

    private static void setStaticPrimarySurface(
            int[] raw, int[] prepared) {
        set(raw, RenderStageSchema.RAW_WAVEFRONT, "primary.material", 3, 1.0F);
        set(raw, RenderStageSchema.RAW_WAVEFRONT, "primary.position", 2, -1.0F);
        set(raw, RenderStageSchema.RAW_WAVEFRONT, "display.position", 2, -1.0F);
        set(prepared, RenderStageSchema.PREPARED_NRD, "primary.view_z", 0, 1.0F);
        set(prepared, RenderStageSchema.PREPARED_NRD, "fsr.depth", 0, 0.05F);
    }

    static NrdPreparationReplayInput preparation(
            RenderReplayCapture source, int frameIndex, boolean restart) {
        NrdPreparationReplayInput input = source.nrdPreparation();
        return new NrdPreparationReplayInput(
                input.currentCamera(),
                input.historyCamera(),
                input.frameTimeNanos() + 10_000_000L,
                input.sceneRevision(),
                input.textureRevision(),
                input.currentJitterX(),
                input.currentJitterY(),
                input.historyJitterX(),
                input.historyJitterY(),
                frameIndex,
                restart,
                restart,
                10.0F,
                input.sunDirection(),
                input.diagnosticMode(),
                input.nativeValidation());
    }

    static RenderReplayCapture withPreparation(
            RenderReplayCapture source,
            NrdPreparationReplayInput preparation) {
        return new RenderReplayCapture(
                source.platform(),
                source.binary(),
                source.frame(),
                preparation,
                source.rawWavefront(),
                source.preparedNrd(),
                source.postNrd());
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
