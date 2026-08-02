package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.shader.ShaderAbi;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.KHRRayTracingPipeline;

final class RayTracingPipelineContractTest {
    private static final int SPIRV_OP_TYPE_INT = 21;
    private static final int SPIRV_OP_TYPE_FLOAT = 22;
    private static final int SPIRV_OP_TYPE_VECTOR = 23;
    private static final int SPIRV_OP_TYPE_RUNTIME_ARRAY = 29;
    private static final int SPIRV_OP_TYPE_STRUCT = 30;
    private static final int SPIRV_OP_TYPE_POINTER = 32;
    private static final int SPIRV_OP_VARIABLE = 59;
    private static final int SPIRV_OP_DECORATE = 71;
    private static final int SPIRV_OP_MEMBER_DECORATE = 72;
    private static final int SPIRV_DECORATION_ARRAY_STRIDE = 6;
    private static final int SPIRV_DECORATION_BINDING = 33;
    private static final int SPIRV_DECORATION_OFFSET = 35;
    private static final int SPIRV_STORAGE_BUFFER = 12;
    private static final int SPIRV_STORAGE_RAY_PAYLOAD = 5338;
    private static final int SPIRV_STORAGE_INCOMING_RAY_PAYLOAD = 5342;

    @Test
    void blockAtlasIsVisibleToEveryShaderStageThatSamplesIt() {
        int expected = KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR;

        assertEquals(expected, RayTracingPipeline.BLOCK_ATLAS_STAGES);
    }

    @Test
    void labPbrSpecularAtlasIsVisibleToSurfaceAndEmissionConsumers() {
        int expected = KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;

        assertEquals(expected, RayTracingPipeline.LABPBR_SPECULAR_STAGES);
    }

    @Test
    void runtimeTransmissionLookupHasTheExpectedShape() {
        assertEquals(44, BsdfLookupTable.WIDTH);
        assertEquals(32, BsdfLookupTable.HEIGHT);
        assertEquals(159, BsdfLookupTable.DEPTH);
        assertEquals(44 * 32 * 159 * 4 * Short.BYTES, BsdfLookupTable.BYTE_SIZE);
    }

    @Test
    void rayTracingShaderGroupsHaveTheExpectedShape() {
        assertEquals(47, RayTracingPipeline.DESCRIPTOR_BINDING_COUNT);
        assertEquals(39, RayTracingPipeline.STORAGE_IMAGE_DESCRIPTOR_COUNT);
        assertEquals(2, RayTracingPipeline.MISS_GROUP_COUNT);
        assertEquals(6, RayTracingPipeline.HIT_GROUP_COUNT);
        assertEquals(8, RayTracingPipeline.RAYGEN_GROUP_COUNT);
        assertEquals(5, RayTracingPipeline.RAYGEN_MODULE_COUNT);
        assertEquals(5, RayTracingPipeline.RAYGEN_SHADER_STAGE_COUNT);
        assertEquals(6, RayTracingPipeline.FIXED_SHADER_MODULE_COUNT);
        assertEquals(2, RayTracingPipeline.ANY_HIT_SHADER_STAGE_COUNT);
        assertEquals(12, RayTracingPipeline.WAVEFRONT_STEP_DISPATCH_COUNT);
        assertEquals(15, RayTracingPipeline.WAVEFRONT_DISPATCH_COUNT);
        assertEquals(3, RayTracingPipeline.raygenShaderStage(0));
        assertEquals(0, RayTracingPipeline.raygenShaderStage(1));
        assertEquals(1, RayTracingPipeline.raygenShaderStage(2));
        assertEquals(1, RayTracingPipeline.raygenShaderStage(3));
        assertEquals(2, RayTracingPipeline.raygenShaderStage(4));
        assertEquals(2, RayTracingPipeline.raygenShaderStage(5));
        assertEquals(3, RayTracingPipeline.raygenShaderStage(6));
        assertEquals(4, RayTracingPipeline.raygenShaderStage(7));
        assertEquals(515, RayTracingPipeline.raygenRecordStage(0));
        assertEquals(0, RayTracingPipeline.raygenRecordStage(1));
        assertEquals(1, RayTracingPipeline.raygenRecordStage(2));
        assertEquals(257, RayTracingPipeline.raygenRecordStage(3));
        assertEquals(2, RayTracingPipeline.raygenRecordStage(4));
        assertEquals(258, RayTracingPipeline.raygenRecordStage(5));
        assertEquals(3, RayTracingPipeline.raygenRecordStage(6));
        assertEquals(0, RayTracingPipeline.raygenRecordStage(7));
        assertEquals(2, RayTracingPipeline.wavefrontStepGroup(0));
        assertEquals(3, RayTracingPipeline.wavefrontStepGroup(1));
        assertEquals(4, RayTracingPipeline.wavefrontTailGroup(0));
        assertEquals(5, RayTracingPipeline.wavefrontTailGroup(1));
    }

    @Test
    void optimizedRayTracingModulesPreservePayloadAbi() throws IOException {
        String tracePayload = "struct(vec3(f32),f32,vec3(f32),"
                + "u32,u32,u32,u32,u32,u32,u32,u32,u32)";
        String shadowPayload = "struct(vec3(f32),f32,f32)";

        for (String shader : List.of(
                "world.rmiss.spv",
                "world.rchit.spv")) {
            assertEquals(
                    Set.of(tracePayload),
                    payloadShapes(shader, SPIRV_STORAGE_INCOMING_RAY_PAYLOAD));
        }
        for (String shader : List.of(
                "shadow.rmiss.spv",
                "shadow.rchit.spv",
                "shadow.rahit.spv")) {
            assertEquals(
                    Set.of(shadowPayload),
                    payloadShapes(shader, SPIRV_STORAGE_INCOMING_RAY_PAYLOAD));
        }
        assertEquals(
                Set.of(shadowPayload),
                payloadShapes(
                        "sun_shadow.rgen.spv",
                        SPIRV_STORAGE_RAY_PAYLOAD));
        for (String shader : List.of(
                "wavefront_head.rgen.spv",
                "wavefront_step.rgen.spv",
                "wavefront_tail.rgen.spv",
                "wavefront_head_ser.rgen.spv",
                "wavefront_step_ser.rgen.spv",
                "wavefront_tail_ser.rgen.spv")) {
            Set<String> payloads = payloadShapes(shader, SPIRV_STORAGE_RAY_PAYLOAD);
            assertTrue(payloads.contains(tracePayload), shader);
            assertTrue(payloads.contains(shadowPayload), shader);
        }
    }

    @Test
    void fixedWavefrontSlotsScaleExactlyWithTheRenderExtent() {
        assertEquals(
                1_857_945_632L,
                RayTracingPipeline.wavefrontPathBytes(3840, 2160));
        assertEquals(
                1_592_524_800L,
                RayTracingPipeline.wavefrontQueueOffset(3840, 2160));
        assertEquals(
                265_420_832L,
                RayTracingPipeline.wavefrontQueueBytes(3840, 2160));
        assertEquals(
                1_725_235_200L,
                RayTracingPipeline.wavefrontQueueCommandOffset(3840, 2160));
        assertThrows(
                IllegalArgumentException.class,
                () -> RayTracingPipeline.wavefrontPathBytes(0, 2160));
        assertThrows(
                ArithmeticException.class,
                () -> RayTracingPipeline.wavefrontPathBytes(Integer.MAX_VALUE, Integer.MAX_VALUE));
        RayTracingPipeline.validateWavefrontRanges(3840, 2160, 0xffff_ffffL);
        assertThrows(
                IllegalStateException.class,
                () -> RayTracingPipeline.validateWavefrontRanges(
                        1920, 1080, 128L * 1024L * 1024L));
        RayTracingPipeline.validateWavefrontDispatch(3840, 2160, 1 << 25);
        assertThrows(
                IllegalStateException.class,
                () -> RayTracingPipeline.validateWavefrontDispatch(
                        3840, 2160, 1 << 23));
    }

    @Test
    void compiledWavefrontStorageUsesTheCompactedRecordAbi() throws IOException {
        int[] words = spirvWords("wavefront_step.rgen.spv");
        Map<Integer, SpirvType> types = new HashMap<>();
        Map<Integer, Integer> bindings = new HashMap<>();
        Map<Integer, Integer> arrayStrides = new HashMap<>();
        Map<Long, Integer> memberOffsets = new HashMap<>();
        List<SpirvVariable> variables = new ArrayList<>();
        for (int offset = 5; offset < words.length; ) {
            int instruction = words[offset];
            int wordCount = instruction >>> 16;
            int opcode = instruction & 0xffff;
            if (wordCount <= 0 || offset + wordCount > words.length) {
                throw new IllegalArgumentException("Malformed SPIR-V instruction");
            }
            if (opcode == SPIRV_OP_TYPE_RUNTIME_ARRAY
                    || opcode == SPIRV_OP_TYPE_STRUCT
                    || opcode == SPIRV_OP_TYPE_POINTER) {
                types.put(
                        words[offset + 1],
                        new SpirvType(
                                opcode,
                                Arrays.copyOfRange(
                                        words, offset + 2, offset + wordCount)));
            } else if (opcode == SPIRV_OP_VARIABLE) {
                variables.add(new SpirvVariable(
                        words[offset + 1], words[offset + 2], words[offset + 3]));
            } else if (opcode == SPIRV_OP_DECORATE && wordCount >= 4) {
                int target = words[offset + 1];
                int decoration = words[offset + 2];
                if (decoration == SPIRV_DECORATION_BINDING) {
                    bindings.put(target, words[offset + 3]);
                } else if (decoration == SPIRV_DECORATION_ARRAY_STRIDE) {
                    arrayStrides.put(target, words[offset + 3]);
                }
            } else if (opcode == SPIRV_OP_MEMBER_DECORATE
                    && wordCount >= 5
                    && words[offset + 3] == SPIRV_DECORATION_OFFSET) {
                memberOffsets.put(
                        spirvMember(words[offset + 1], words[offset + 2]),
                        words[offset + 4]);
            }
            offset += wordCount;
        }

        SpirvVariable paths = variables.stream()
                .filter(variable -> variable.storageClass() == SPIRV_STORAGE_BUFFER)
                .filter(variable -> bindings.getOrDefault(variable.identifier(), -1)
                        == ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS)
                .findFirst()
                .orElseThrow();
        SpirvType pointer = requireType(types, paths.type());
        SpirvType block = requireType(types, pointer.operands()[1]);
        int arrayIdentifier = block.operands()[0];
        SpirvType array = requireType(types, arrayIdentifier);
        int recordIdentifier = array.operands()[0];
        SpirvType record = requireType(types, recordIdentifier);
        int transportIdentifier = record.operands()[0];
        SpirvType transport = requireType(types, transportIdentifier);

        assertEquals(SPIRV_OP_TYPE_RUNTIME_ARRAY, array.opcode());
        assertEquals(ShaderAbi.WAVEFRONT_PATH_RECORD_SIZE, arrayStrides.get(arrayIdentifier));
        assertEquals(2, record.operands().length);
        assertEquals(0, memberOffsets.get(spirvMember(recordIdentifier, 0)));
        assertEquals(80, memberOffsets.get(spirvMember(recordIdentifier, 1)));
        assertEquals(6, transport.operands().length);
        int[] expectedOffsets = {0, 16, 32, 48, 64, 72};
        for (int member = 0; member < expectedOffsets.length; member++) {
            assertEquals(
                    expectedOffsets[member],
                    memberOffsets.get(spirvMember(transportIdentifier, member)));
        }
    }

    @Test
    void deferredCompilationClampsDriverConcurrencyToTheHost() {
        assertEquals(1, RayTracingPipeline.deferredWorkerCount(0, 32));
        assertEquals(2, RayTracingPipeline.deferredWorkerCount(2, 32));
        assertEquals(8, RayTracingPipeline.deferredWorkerCount(32, 8));
        assertEquals(32, RayTracingPipeline.deferredWorkerCount(-1, 32));
        assertEquals(1, RayTracingPipeline.deferredWorkerCount(8, 0));
    }

    private static Set<String> payloadShapes(
            String shader, int storageClass) throws IOException {
        int[] words = spirvWords(shader);

        Map<Integer, SpirvType> types = new HashMap<>();
        List<SpirvVariable> variables = new ArrayList<>();
        for (int offset = 5; offset < words.length; ) {
            int instruction = words[offset];
            int wordCount = instruction >>> 16;
            int opcode = instruction & 0xffff;
            if (wordCount <= 0 || offset + wordCount > words.length) {
                throw new IllegalArgumentException("Malformed SPIR-V instruction");
            }
            if (opcode == SPIRV_OP_TYPE_INT
                    || opcode == SPIRV_OP_TYPE_FLOAT
                    || opcode == SPIRV_OP_TYPE_VECTOR
                    || opcode == SPIRV_OP_TYPE_STRUCT
                    || opcode == SPIRV_OP_TYPE_POINTER) {
                types.put(
                        words[offset + 1],
                        new SpirvType(
                                opcode,
                                Arrays.copyOfRange(
                                        words,
                                        offset + 2,
                                        offset + wordCount)));
            } else if (opcode == SPIRV_OP_VARIABLE) {
                variables.add(new SpirvVariable(
                        words[offset + 1],
                        words[offset + 2],
                        words[offset + 3]));
            }
            offset += wordCount;
        }

        Set<String> result = new HashSet<>();
        for (SpirvVariable variable : variables) {
            if (variable.storageClass() != storageClass) {
                continue;
            }
            SpirvType pointer = requireType(types, variable.type());
            if (pointer.opcode() != SPIRV_OP_TYPE_POINTER
                    || pointer.operands()[0] != storageClass) {
                throw new IllegalArgumentException("Malformed SPIR-V payload pointer");
            }
            result.add(typeShape(types, pointer.operands()[1]));
        }
        return result;
    }

    private static int[] spirvWords(String shader) throws IOException {
        String resource = "/prime/shaders/" + shader;
        byte[] bytes;
        try (InputStream input =
                RayTracingPipelineContractTest.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalArgumentException(
                        "Missing compiled shader resource " + resource);
            }
            bytes = input.readAllBytes();
        }
        if ((bytes.length & 3) != 0) {
            throw new IllegalArgumentException("Malformed SPIR-V byte length");
        }
        int[] words = new int[bytes.length / Integer.BYTES];
        ByteBuffer.wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asIntBuffer()
                .get(words);
        if (words.length < 5 || words[0] != 0x0723_0203) {
            throw new IllegalArgumentException("Malformed SPIR-V header");
        }
        return words;
    }

    private static long spirvMember(int type, int member) {
        return (Integer.toUnsignedLong(type) << 32) | Integer.toUnsignedLong(member);
    }

    private static String typeShape(
            Map<Integer, SpirvType> types, int identifier) {
        SpirvType type = requireType(types, identifier);
        return switch (type.opcode()) {
            case SPIRV_OP_TYPE_INT ->
                    (type.operands()[1] == 0 ? "u" : "i") + type.operands()[0];
            case SPIRV_OP_TYPE_FLOAT -> "f" + type.operands()[0];
            case SPIRV_OP_TYPE_VECTOR -> "vec" + type.operands()[1]
                    + "(" + typeShape(types, type.operands()[0]) + ")";
            case SPIRV_OP_TYPE_STRUCT -> "struct("
                    + Arrays.stream(type.operands())
                            .mapToObj(member -> typeShape(types, member))
                            .reduce((left, right) -> left + "," + right)
                            .orElse("")
                    + ")";
            default -> throw new IllegalArgumentException(
                    "Unsupported SPIR-V payload type opcode " + type.opcode());
        };
    }

    private static SpirvType requireType(
            Map<Integer, SpirvType> types, int identifier) {
        SpirvType type = types.get(identifier);
        if (type == null) {
            throw new IllegalArgumentException(
                    "Missing SPIR-V type " + Integer.toUnsignedString(identifier));
        }
        return type;
    }

    private record SpirvType(int opcode, int[] operands) {}

    private record SpirvVariable(int type, int identifier, int storageClass) {}
}
