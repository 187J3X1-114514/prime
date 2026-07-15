package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class ShaderAbiTest {
    @Test
    void fixedRecordSizesAndBindingsMatchTheContract() {
        assertEquals(32, ShaderAbi.PRIMITIVE_RECORD_SIZE);
        assertEquals(64, ShaderAbi.SECTION_RECORD_SIZE);
        assertEquals(32, ShaderAbi.LIGHT_NODE_SIZE);
        assertEquals(4, ShaderAbi.LIGHT_NODE_FORWARD_SIZE);
        assertEquals(4, ShaderAbi.LIGHT_NODE_REVERSE_SIZE);
        assertEquals(96, ShaderAbi.LIGHT_EMITTER_SIZE);
        assertEquals(16, ShaderAbi.LIGHT_CELL_SIZE);
        assertEquals(48, ShaderAbi.SECTION_LIGHT_HEADER_SIZE);
        assertEquals(32, ShaderAbi.INTEGRATOR_RECORD_SIZE);
        assertEquals(96, ShaderAbi.PATH_STATE_SIZE);
        assertEquals(64, ShaderAbi.TRACE_PAYLOAD_SIZE);
        assertEquals(64, ShaderAbi.SURFACE_INTERACTION_SIZE);
        assertEquals(128, ShaderAbi.PUSH_CONSTANT_SIZE);
        assertEquals(0, ShaderAbi.DESCRIPTOR_TLAS);
        assertEquals(1, ShaderAbi.DESCRIPTOR_OUTPUT_IMAGE);
        assertEquals(2, ShaderAbi.DESCRIPTOR_BLOCK_ATLAS);
        assertEquals(3, ShaderAbi.DESCRIPTOR_ACCUMULATION_IMAGE);
        assertEquals(4, ShaderAbi.DESCRIPTOR_SKY_VIEW);
        assertEquals(5, ShaderAbi.DESCRIPTOR_TRANSMITTANCE_LOW);
        assertEquals(6, ShaderAbi.DESCRIPTOR_TRANSMITTANCE_HIGH);
        assertEquals(7, ShaderAbi.DESCRIPTOR_AERIAL_RADIANCE);
        assertEquals(8, ShaderAbi.DESCRIPTOR_AERIAL_TRANSMITTANCE);
        assertEquals(14, ShaderAbi.DESCRIPTOR_NRD_PRIMARY_POSITION);
        assertEquals(15, ShaderAbi.DESCRIPTOR_NRD_NOISY_SPECULAR);
        assertEquals(16, ShaderAbi.DESCRIPTOR_NRD_SPECULAR_MATERIAL);
        assertEquals(17, ShaderAbi.DESCRIPTOR_TRANSMISSION_GGX_ENERGY);
        assertEquals(0x80000000, ShaderAbi.PATH_CAMERA_IN_WATER_MASK);
        assertEquals(0x7fff, ShaderAbi.PATH_JITTER_PHASE_MASK);
        assertEquals("linear-rec2020-d65", ShaderAbi.WORKING_COLOR_SPACE);
        assertEquals("srgb", ShaderAbi.TEXTURE_COLOR_ENCODING);
        assertEquals("srgb", ShaderAbi.DISPLAY_COLOR_ENCODING);
        assertEquals("screen-2.5d", ShaderAbi.NRD_MOTION_SPACE);
        assertEquals("reblur-diffuse-specular", ShaderAbi.NRD_DENOISER);
        assertEquals("hillaire-8wave-rec2020-d65", ShaderAbi.ATMOSPHERE_SPECTRAL_MODEL);
        assertEquals(10.0F, ShaderAbi.ATMOSPHERE_SPACE_SUN_INTENSITY);
        assertEquals(0.00471F, ShaderAbi.ATMOSPHERE_SUN_ANGULAR_RADIUS_RADIANS);
        assertEquals(-64.0F, ShaderAbi.ATMOSPHERE_WORLD_SEA_LEVEL_Y);
        assertEquals(0.004F, ShaderAbi.ATMOSPHERE_WORLD_UNIT_SCALE_KM);
        assertEquals(64.0F, ShaderAbi.ATMOSPHERE_AERIAL_MAX_DISTANCE_KM);
    }

    @Test
    void generatedOffsetsMatchTheStd430Layout() {
        assertEquals(0, ShaderAbi.PRIMITIVE_UV0_OFFSET);
        assertEquals(12, ShaderAbi.PRIMITIVE_TINT_OFFSET);
        assertEquals(16, ShaderAbi.PRIMITIVE_NORMAL_OFFSET);
        assertEquals(28, ShaderAbi.PRIMITIVE_UV_DENSITY_OFFSET);
        assertEquals(0, ShaderAbi.SECTION_PRIMITIVE_ADDRESS_OFFSET);
        assertEquals(8, ShaderAbi.SECTION_LIGHT_ADDRESS_OFFSET);
        assertEquals(16, ShaderAbi.SECTION_WORLD_LIGHT_ADDRESS_OFFSET);
        assertEquals(24, ShaderAbi.SECTION_OPAQUE_BASE_OFFSET);
        assertEquals(28, ShaderAbi.SECTION_CUTOUT_BASE_OFFSET);
        assertEquals(32, ShaderAbi.SECTION_WORLD_LEAF_NODE_OFFSET);
        assertEquals(40, ShaderAbi.SECTION_WORLD_LIGHT_FORWARD_ADDRESS_OFFSET);
        assertEquals(48, ShaderAbi.SECTION_TRANSLATION_OFFSET);
        assertEquals(60, ShaderAbi.SECTION_WORLD_LIGHT_NODE_COUNT_OFFSET);
        assertEquals(0, ShaderAbi.LIGHT_NODE_BOUNDS_MIN_POWER_OFFSET);
        assertEquals(0, ShaderAbi.LIGHT_NODE_FORWARD_CHILD_OR_LEAF_OFFSET);
        assertEquals(0, ShaderAbi.LIGHT_NODE_REVERSE_PARENT_OFFSET);
        assertEquals(0, ShaderAbi.LIGHT_EMITTER_CORNER_AREA_OFFSET);
        assertEquals(64, ShaderAbi.LIGHT_EMITTER_UVS_TINT_OFFSET);
        assertEquals(80, ShaderAbi.LIGHT_EMITTER_METADATA_OFFSET);
        assertEquals(0, ShaderAbi.LIGHT_CELL_ALIAS_PROBABILITY_OFFSET);
        assertEquals(8, ShaderAbi.LIGHT_CELL_PROBABILITY_MASS_OFFSET);
        assertEquals(12, ShaderAbi.LIGHT_CELL_GEOMETRY_OFFSET);
        assertEquals(0, ShaderAbi.SECTION_LIGHT_HEADER_NODE_ADDRESS_OFFSET);
        assertEquals(8, ShaderAbi.SECTION_LIGHT_HEADER_FORWARD_ADDRESS_OFFSET);
        assertEquals(16, ShaderAbi.SECTION_LIGHT_HEADER_REVERSE_ADDRESS_OFFSET);
        assertEquals(24, ShaderAbi.SECTION_LIGHT_HEADER_EMITTER_ADDRESS_OFFSET);
        assertEquals(32, ShaderAbi.SECTION_LIGHT_HEADER_CELL_ADDRESS_OFFSET);
        assertEquals(40, ShaderAbi.SECTION_LIGHT_HEADER_ROOT_OFFSET);
        assertEquals(44, ShaderAbi.SECTION_LIGHT_HEADER_EMITTER_COUNT_OFFSET);
        assertEquals(0, ShaderAbi.INTEGRATOR_SUN_DIRECTION_INTENSITY_OFFSET);
        assertEquals(16, ShaderAbi.INTEGRATOR_ENVIRONMENT_RADIANCE_OFFSET);
        assertEquals(0, ShaderAbi.PATH_STATE_PHYSICAL_ORIGIN_OFFSET);
        assertEquals(16, ShaderAbi.PATH_STATE_TRACE_ORIGIN_OFFSET);
        assertEquals(80, ShaderAbi.PATH_STATE_PIXEL_OFFSET);
        assertEquals(92, ShaderAbi.PATH_STATE_SAMPLE_EPOCH_OFFSET);
        assertEquals(0, ShaderAbi.TRACE_PAYLOAD_POSITION_OFFSET);
        assertEquals(16, ShaderAbi.TRACE_PAYLOAD_GEOMETRIC_NORMAL_OFFSET);
        assertEquals(44, ShaderAbi.TRACE_PAYLOAD_TRACE_KIND_OFFSET);
        assertEquals(48, ShaderAbi.TRACE_PAYLOAD_SECTION_INDEX_OFFSET);
        assertEquals(52, ShaderAbi.TRACE_PAYLOAD_EMITTER_INDEX_OFFSET);
        assertEquals(0, ShaderAbi.SURFACE_POSITION_OFFSET);
        assertEquals(16, ShaderAbi.SURFACE_GEOMETRIC_NORMAL_OFFSET);
        assertEquals(44, ShaderAbi.SURFACE_MATERIAL_FLAGS_OFFSET);
        assertEquals(48, ShaderAbi.SURFACE_SECTION_INDEX_OFFSET);
        assertEquals(52, ShaderAbi.SURFACE_EMITTER_INDEX_OFFSET);
        assertEquals(0, ShaderAbi.PUSH_INVERSE_VIEW_PROJECTION_OFFSET);
        assertEquals(64, ShaderAbi.PUSH_CAMERA_POSITION_OFFSET);
        assertEquals(76, ShaderAbi.PUSH_ATMOSPHERE_EYE_RADIUS_KM_OFFSET);
        assertEquals(80, ShaderAbi.PUSH_SECTION_TABLE_ADDRESS_OFFSET);
        assertEquals(88, ShaderAbi.PUSH_OUTPUT_EXTENT_OFFSET);
        assertEquals(96, ShaderAbi.PUSH_SUN_DIRECTION_OFFSET);
        assertEquals(108, ShaderAbi.PUSH_RAY_CONE_OFFSET);
        assertEquals(112, ShaderAbi.PUSH_PATH_OFFSET);
    }
}
