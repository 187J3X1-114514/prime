package dev.prime.render.replay;

import java.util.Objects;

/** One replayable semantic frame plus the exact raw and prepared GPU observations. */
public record RenderReplayCapture(
        RenderPlatformFingerprint platform,
        RenderBinaryFingerprint binary,
        RayTraceReplayInput frame,
        NrdPreparationReplayInput nrdPreparation,
        CapturedRenderStage rawWavefront,
        CapturedRenderStage preparedNrd) {
    public RenderReplayCapture {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(binary, "binary");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(nrdPreparation, "nrdPreparation");
        Objects.requireNonNull(rawWavefront, "rawWavefront");
        Objects.requireNonNull(preparedNrd, "preparedNrd");
        if (rawWavefront.schema() != RenderStageSchema.RAW_WAVEFRONT
                || preparedNrd.schema() != RenderStageSchema.PREPARED_NRD) {
            throw new IllegalArgumentException(
                    "Replay capture contains the wrong stage schemas");
        }
        if (rawWavefront.width() != frame.width()
                || rawWavefront.height() != frame.height()
                || preparedNrd.width() != frame.width()
                || preparedNrd.height() != frame.height()) {
            throw new IllegalArgumentException(
                    "Replay stage extents do not match the semantic frame");
        }
    }
}
