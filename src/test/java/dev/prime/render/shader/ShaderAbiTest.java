package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class ShaderAbiTest {
    @Test
    void fixedRecordSizesAndBindingsMatchTheContract() {
        assertEquals(32, ShaderAbi.PRIMITIVE_RECORD_SIZE);
        assertEquals(16, ShaderAbi.SECTION_RECORD_SIZE);
        assertEquals(32, ShaderAbi.INTEGRATOR_RECORD_SIZE);
        assertEquals(96, ShaderAbi.PATH_STATE_SIZE);
        assertEquals(48, ShaderAbi.TRACE_PAYLOAD_SIZE);
        assertEquals(48, ShaderAbi.SURFACE_INTERACTION_SIZE);
        assertEquals(128, ShaderAbi.PUSH_CONSTANT_SIZE);
        assertEquals(0, ShaderAbi.DESCRIPTOR_TLAS);
        assertEquals(1, ShaderAbi.DESCRIPTOR_OUTPUT_IMAGE);
        assertEquals(2, ShaderAbi.DESCRIPTOR_BLOCK_ATLAS);
        assertEquals(3, ShaderAbi.DESCRIPTOR_ACCUMULATION_IMAGE);
        assertEquals("linear-rec2020-d65", ShaderAbi.WORKING_COLOR_SPACE);
        assertEquals("srgb", ShaderAbi.TEXTURE_COLOR_ENCODING);
        assertEquals("srgb", ShaderAbi.DISPLAY_COLOR_ENCODING);
    }

    @Test
    void generatedOffsetsMatchTheStd430Layout() {
        assertEquals(0, ShaderAbi.PRIMITIVE_UV0_OFFSET);
        assertEquals(12, ShaderAbi.PRIMITIVE_TINT_OFFSET);
        assertEquals(16, ShaderAbi.PRIMITIVE_NORMAL_OFFSET);
        assertEquals(28, ShaderAbi.PRIMITIVE_RESERVED1_OFFSET);
        assertEquals(0, ShaderAbi.SECTION_PRIMITIVE_ADDRESS_OFFSET);
        assertEquals(8, ShaderAbi.SECTION_OPAQUE_BASE_OFFSET);
        assertEquals(12, ShaderAbi.SECTION_CUTOUT_BASE_OFFSET);
        assertEquals(0, ShaderAbi.INTEGRATOR_SUN_DIRECTION_INTENSITY_OFFSET);
        assertEquals(16, ShaderAbi.INTEGRATOR_ENVIRONMENT_RADIANCE_OFFSET);
        assertEquals(0, ShaderAbi.PATH_STATE_PHYSICAL_ORIGIN_OFFSET);
        assertEquals(16, ShaderAbi.PATH_STATE_TRACE_ORIGIN_OFFSET);
        assertEquals(80, ShaderAbi.PATH_STATE_PIXEL_OFFSET);
        assertEquals(92, ShaderAbi.PATH_STATE_SAMPLE_EPOCH_OFFSET);
        assertEquals(0, ShaderAbi.TRACE_PAYLOAD_POSITION_OFFSET);
        assertEquals(16, ShaderAbi.TRACE_PAYLOAD_GEOMETRIC_NORMAL_OFFSET);
        assertEquals(44, ShaderAbi.TRACE_PAYLOAD_TRACE_KIND_OFFSET);
        assertEquals(0, ShaderAbi.SURFACE_POSITION_OFFSET);
        assertEquals(16, ShaderAbi.SURFACE_GEOMETRIC_NORMAL_OFFSET);
        assertEquals(44, ShaderAbi.SURFACE_MATERIAL_FLAGS_OFFSET);
        assertEquals(0, ShaderAbi.PUSH_INVERSE_VIEW_PROJECTION_OFFSET);
        assertEquals(64, ShaderAbi.PUSH_CAMERA_POSITION_OFFSET);
        assertEquals(80, ShaderAbi.PUSH_SECTION_TABLE_ADDRESS_OFFSET);
        assertEquals(88, ShaderAbi.PUSH_OUTPUT_EXTENT_OFFSET);
        assertEquals(96, ShaderAbi.PUSH_INTEGRATOR_ADDRESS_OFFSET);
        assertEquals(112, ShaderAbi.PUSH_PATH_OFFSET);
    }
}
