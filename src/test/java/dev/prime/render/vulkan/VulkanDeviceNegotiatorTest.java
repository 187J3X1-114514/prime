package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class VulkanDeviceNegotiatorTest {
    @Test
    void invocationReorderSbtLimitUsesUnsignedVulkanSemantics() {
        assertTrue(VulkanDeviceNegotiator.supportsSbtRecordIndex(-1, 1));
        assertTrue(VulkanDeviceNegotiator.supportsSbtRecordIndex(1, 1));
        assertFalse(VulkanDeviceNegotiator.supportsSbtRecordIndex(0, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> VulkanDeviceNegotiator.supportsSbtRecordIndex(-1, -1));
    }
}
