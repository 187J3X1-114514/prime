package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class ShaderAbiTest {
    @Test
    void fixedRecordSizesAndBindingsMatchTheContract() {
        assertEquals(32, ShaderAbi.PRIMITIVE_RECORD_SIZE);
        assertEquals(16, ShaderAbi.SECTION_RECORD_SIZE);
        assertEquals(96, ShaderAbi.PUSH_CONSTANT_SIZE);
        assertEquals(0, ShaderAbi.DESCRIPTOR_TLAS);
        assertEquals(1, ShaderAbi.DESCRIPTOR_OUTPUT_IMAGE);
        assertEquals(2, ShaderAbi.DESCRIPTOR_BLOCK_ATLAS);
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
        assertEquals(0, ShaderAbi.PUSH_INVERSE_VIEW_PROJECTION_OFFSET);
        assertEquals(64, ShaderAbi.PUSH_CAMERA_POSITION_OFFSET);
        assertEquals(80, ShaderAbi.PUSH_SECTION_TABLE_ADDRESS_OFFSET);
        assertEquals(88, ShaderAbi.PUSH_OUTPUT_EXTENT_OFFSET);
    }
}
