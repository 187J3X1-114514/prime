package dev.prime.render.vulkan.nrd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class NrdNativeTest {
    @Test
    void bundledBridgeCreatesReblurDiffuseSpecularDispatches() {
        try (NrdNative.Instance instance = NrdNative.create(64, 48)) {
            NrdNative.Description description = instance.description();
            assertEquals(NrdNative.EXPECTED_NRD_VERSION, description.nrdVersion());
            assertEquals("main", description.shaderEntryPoint());
            assertEquals(0, description.resourcesSpaceIndex());
            assertEquals(1, description.constantBufferAndSamplersSpaceIndex());
            assertEquals(2, description.samplers().size());
            assertFalse(description.pipelines().isEmpty());
            assertFalse(description.permanentPool().isEmpty());
            for (NrdNative.Pipeline pipeline : description.pipelines()) {
                assertEquals(
                        0x07230203,
                        ByteBuffer.wrap(pipeline.spirv()).order(ByteOrder.LITTLE_ENDIAN).getInt());
                Set<Integer> requiredBindings = new HashSet<>();
                if (pipeline.hasConstantData()) {
                    requiredBindings.add(description.constantBufferOffset()
                            + description.constantBufferRegisterIndex());
                }
                int sampledIndex = 0;
                int storageIndex = 0;
                for (NrdNative.PipelineRange range : pipeline.ranges()) {
                    for (int index = 0; index < range.descriptorsNum(); index++) {
                        if (range.descriptorType() == NrdNative.DESCRIPTOR_TEXTURE) {
                            requiredBindings.add(description.textureOffset()
                                    + description.resourcesBaseRegisterIndex()
                                    + sampledIndex++);
                        } else {
                            requiredBindings.add(description.storageTextureOffset()
                                    + description.resourcesBaseRegisterIndex()
                                    + storageIndex++);
                        }
                    }
                }
                Set<DescriptorBinding> descriptors = descriptorBindings(pipeline.spirv());
                Set<Integer> actualBindings = new HashSet<>();
                for (DescriptorBinding descriptor : descriptors) {
                    actualBindings.add(descriptor.binding());
                    int expectedSet = description.samplers().contains(descriptor.binding())
                                    || descriptor.binding() == description.constantBufferOffset()
                                            + description.constantBufferRegisterIndex()
                            ? description.constantBufferAndSamplersSpaceIndex()
                            : description.resourcesSpaceIndex();
                    assertEquals(expectedSet, descriptor.set());
                }
                Set<Integer> allowedBindings = new HashSet<>(requiredBindings);
                allowedBindings.addAll(description.samplers());
                assertTrue(allowedBindings.containsAll(actualBindings));
                Set<Integer> descriptorSets = new HashSet<>();
                descriptors.forEach(descriptor -> descriptorSets.add(descriptor.set()));
                assertTrue(descriptorSets.contains(description.resourcesSpaceIndex()));
                assertTrue(Set.of(
                                description.resourcesSpaceIndex(),
                                description.constantBufferAndSamplersSpaceIndex())
                        .containsAll(descriptorSets));
            }

            float[] identity = new float[] {
                1.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 1.0f, 0.0f,
                0.0f, 0.0f, 0.0f, 1.0f
            };
            instance.setFrameSettings(new NrdNative.FrameSettings(
                    identity,
                    identity,
                    identity,
                    identity,
                    new float[] {0.25f, -0.25f},
                    new float[] {0.0f, 0.0f},
                    64,
                    48,
                    64,
                    48,
                    0,
                    true,
                    16.67f,
                    1_000.0f,
                    true));
            var dispatches = instance.getDispatches();
            assertTrue(dispatches.size() >= 7);
            assertTrue(dispatches.stream().allMatch(dispatch -> dispatch.gridWidth() > 0));
            assertTrue(dispatches.stream().allMatch(dispatch -> dispatch.gridHeight() > 0));
            assertTrue(dispatches.stream().allMatch(dispatch -> !dispatch.resources().isEmpty()));
            Set<Integer> resourceTypes = new HashSet<>();
            dispatches.stream()
                    .flatMap(dispatch -> dispatch.resources().stream())
                    .forEach(resource -> resourceTypes.add(resource.resourceType()));
            assertTrue(resourceTypes.contains(NrdNative.RESOURCE_IN_DIFF_RADIANCE_HITDIST));
            assertTrue(resourceTypes.contains(NrdNative.RESOURCE_IN_SPEC_RADIANCE_HITDIST));
            assertTrue(resourceTypes.contains(NrdNative.RESOURCE_OUT_DIFF_RADIANCE_HITDIST));
            assertTrue(resourceTypes.contains(NrdNative.RESOURCE_OUT_SPEC_RADIANCE_HITDIST));
            assertTrue(resourceTypes.contains(NrdNative.RESOURCE_OUT_VALIDATION));
        }
    }

    @Test
    void bundledBridgeCreatesIndependentTransparentPsrDispatches() {
        try (NrdNative.Instance instance = NrdNative.create(
                64, 48, NrdNative.DenoiserKind.TRANSPARENT_TRANSMISSION)) {
            NrdNative.Description description = instance.description();
            assertEquals(NrdNative.EXPECTED_NRD_VERSION, description.nrdVersion());
            float[] identity = new float[] {
                1.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 1.0f, 0.0f,
                0.0f, 0.0f, 0.0f, 1.0f
            };
            instance.setFrameSettings(new NrdNative.FrameSettings(
                    identity,
                    identity,
                    identity,
                    identity,
                    new float[] {0.0f, 0.0f},
                    new float[] {0.0f, 0.0f},
                    64,
                    48,
                    64,
                    48,
                    0,
                    true,
                    16.67f,
                    1_000.0f,
                    true));
            Set<Integer> resourceTypes = new HashSet<>();
            instance.getDispatches().stream()
                    .flatMap(dispatch -> dispatch.resources().stream())
                    .forEach(resource -> resourceTypes.add(resource.resourceType()));
            assertTrue(resourceTypes.contains(NrdNative.RESOURCE_IN_DIFF_RADIANCE_HITDIST));
            assertTrue(resourceTypes.contains(NrdNative.RESOURCE_OUT_DIFF_RADIANCE_HITDIST));
            assertTrue(resourceTypes.contains(NrdNative.RESOURCE_IN_SPEC_RADIANCE_HITDIST));
            assertTrue(resourceTypes.contains(NrdNative.RESOURCE_OUT_SPEC_RADIANCE_HITDIST));
        }
    }

    private static Set<DescriptorBinding> descriptorBindings(byte[] spirv) {
        ByteBuffer words = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN);
        Map<Integer, Integer> bindings = new HashMap<>();
        Map<Integer, Integer> sets = new HashMap<>();
        for (int offset = 5 * Integer.BYTES; offset < words.limit();) {
            int instruction = words.getInt(offset);
            int wordCount = instruction >>> 16;
            if (wordCount <= 0 || offset + wordCount * Integer.BYTES > words.limit()) {
                throw new IllegalArgumentException("Malformed bundled NRD SPIR-V");
            }
            int opcode = instruction & 0xffff;
            if (opcode == 71 && wordCount >= 4) {
                int target = words.getInt(offset + Integer.BYTES);
                int decoration = words.getInt(offset + 2 * Integer.BYTES);
                int value = words.getInt(offset + 3 * Integer.BYTES);
                if (decoration == 33) {
                    bindings.put(target, value);
                } else if (decoration == 34) {
                    sets.put(target, value);
                }
            }
            offset += wordCount * Integer.BYTES;
        }
        Set<DescriptorBinding> result = new HashSet<>();
        for (Map.Entry<Integer, Integer> binding : bindings.entrySet()) {
            Integer set = sets.get(binding.getKey());
            if (set == null) {
                throw new IllegalArgumentException("Bundled NRD SPIR-V binding has no descriptor set");
            }
            result.add(new DescriptorBinding(set, binding.getValue()));
        }
        return result;
    }

    private record DescriptorBinding(int set, int binding) {}
}
