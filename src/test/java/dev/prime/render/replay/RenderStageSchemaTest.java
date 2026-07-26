package dev.prime.render.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK12;

final class RenderStageSchemaTest {
    @Test
    void rawCaptureKeepsViewZAndPositionsAtTheirProductionFormats() {
        RenderStageSchema schema = RenderStageSchema.RAW_WAVEFRONT;

        assertEquals(
                VK12.VK_FORMAT_R32_SFLOAT,
                schema.format(schema.signalIndex("primary.view_z")));
        assertEquals(
                VK12.VK_FORMAT_R32G32B32A32_SFLOAT,
                schema.format(schema.signalIndex("primary.position")));
        assertEquals(
                VK12.VK_FORMAT_R32G32B32A32_SFLOAT,
                schema.format(schema.signalIndex("reflection.position")));
        assertEquals(
                VK12.VK_FORMAT_R32G32B32A32_SFLOAT,
                schema.format(schema.signalIndex("display.position")));
    }

    @Test
    void preparedCaptureKeepsMotionAndViewZAtTheirProductionFormats() {
        RenderStageSchema schema = RenderStageSchema.PREPARED_NRD;

        assertEquals(
                VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                schema.format(schema.signalIndex("primary.motion")));
        assertEquals(
                VK12.VK_FORMAT_R32_SFLOAT,
                schema.format(schema.signalIndex("primary.view_z")));
        assertEquals(
                VK12.VK_FORMAT_R16_SFLOAT,
                schema.format(schema.signalIndex("sun.penumbra")));
    }
}
