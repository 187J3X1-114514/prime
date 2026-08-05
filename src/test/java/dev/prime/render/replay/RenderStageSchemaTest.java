package dev.prime.render.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class RenderStageSchemaTest {
    @Test
    void rawCaptureKeepsViewZAndPositionsAtTheirProductionFormats() {
        RenderStageSchema schema = RenderStageSchema.RAW_WAVEFRONT;

        assertEquals(
                RenderPixelFormat.R32_FLOAT,
                schema.format(schema.signalIndex("primary.view_z")));
        assertEquals(
                RenderPixelFormat.RGBA32_FLOAT,
                schema.format(schema.signalIndex("primary.position")));
        assertEquals(
                RenderPixelFormat.RGBA32_FLOAT,
                schema.format(schema.signalIndex("reflection.position")));
        assertEquals(
                RenderPixelFormat.RGBA32_FLOAT,
                schema.format(schema.signalIndex("display.position")));
    }

    @Test
    void preparedCaptureKeepsMotionAndViewZAtTheirProductionFormats() {
        RenderStageSchema schema = RenderStageSchema.PREPARED_NRD;

        assertEquals(
                RenderPixelFormat.RGBA16_FLOAT,
                schema.format(schema.signalIndex("primary.motion")));
        assertEquals(
                RenderPixelFormat.R32_FLOAT,
                schema.format(schema.signalIndex("primary.view_z")));
        assertEquals(
                RenderPixelFormat.R16_FLOAT,
                schema.format(schema.signalIndex("sun.penumbra")));
        assertEquals(
                RenderPixelFormat.R32_FLOAT,
                schema.format(schema.signalIndex("fsr.depth")));
        assertEquals(
                RenderPixelFormat.RGBA16_FLOAT,
                schema.format(schema.signalIndex("fsr.motion")));
    }
}
