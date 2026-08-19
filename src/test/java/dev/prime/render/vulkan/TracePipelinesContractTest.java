package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.lwjgl.vulkan.VK12;

final class TracePipelinesContractTest {
    private static final int OP_TYPE_INT = 21;
    private static final int OP_TYPE_FLOAT = 22;
    private static final int OP_TYPE_VECTOR = 23;
    private static final int OP_TYPE_RUNTIME_ARRAY = 29;
    private static final int OP_TYPE_STRUCT = 30;
    private static final int OP_TYPE_POINTER = 32;
    private static final int OP_VARIABLE = 59;
    private static final int OP_DECORATE = 71;
    private static final int OP_MEMBER_DECORATE = 72;
    private static final int OP_GROUP_NON_UNIFORM_ELECT = 333;
    private static final int OP_GROUP_NON_UNIFORM_BROADCAST_FIRST = 338;
    private static final int OP_GROUP_NON_UNIFORM_BALLOT = 339;
    private static final int OP_GROUP_NON_UNIFORM_BALLOT_BIT_COUNT = 342;
    private static final int OP_TRACE_RAY_KHR = 4445;
    private static final int DECORATION_ARRAY_STRIDE = 6;
    private static final int DECORATION_BINDING = 33;
    private static final int DECORATION_DESCRIPTOR_SET = 34;
    private static final int DECORATION_OFFSET = 35;

    private static final int STORAGE_BUFFER = 12;
    private static final int STORAGE_RAY_PAYLOAD = 5338;
    private static final int STORAGE_INCOMING_RAY_PAYLOAD = 5342;

    @Test
    void commandWritesWaitForShaderAndIndirectConsumers() {
        long expectedStages =
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                        | VK12.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT;
        long expectedAccesses =
                VK12.VK_ACCESS_SHADER_READ_BIT
                        | VK12.VK_ACCESS_SHADER_WRITE_BIT
                        | VK12.VK_ACCESS_INDIRECT_COMMAND_READ_BIT;
        assertEquals(expectedStages, WavefrontCommands.COMMAND_WRITE_SOURCE_STAGES);
        assertEquals(expectedAccesses, WavefrontCommands.COMMAND_WRITE_SOURCE_ACCESSES);
    }

    @Test
    void realtimeAndOfflineHaveIndependentSchedulesAndDescriptors() {
        assertEquals(13, RealtimeRayTracingPipeline.RAYGEN_GROUP_COUNT);
        assertEquals(10, RealtimeRayTracingPipeline.RAYGEN_MODULE_COUNT);
        assertEquals(50, RealtimeRayTracingPipeline.dispatchCount(12));
        assertEquals(6, RealtimeRayTracingPipeline.dispatchCount(1));
        assertEquals(258, RealtimeRayTracingPipeline.dispatchCount(64));
        assertEquals(26, RealtimeRayTracingPipeline.DESCRIPTOR_BINDING_COUNT);

        assertEquals(6, OfflineRayTracingPipeline.RAYGEN_GROUP_COUNT);
        assertEquals(4, OfflineRayTracingPipeline.RAYGEN_MODULE_COUNT);
        assertEquals(25, OfflineRayTracingPipeline.dispatchCount(12));
        assertEquals(3, OfflineRayTracingPipeline.dispatchCount(1));
        assertEquals(129, OfflineRayTracingPipeline.dispatchCount(64));
        assertEquals(3, OfflineRayTracingPipeline.DESCRIPTOR_BINDING_COUNT);

        assertEquals(List.of(0, 1, 1, 2, 3, 4, 5, 6, 6, 7, 7, 8, 9),
                java.util.stream.IntStream
                .range(0, RealtimeRayTracingPipeline.RAYGEN_GROUP_COUNT)
                .map(RealtimeRayTracingPipeline::raygenModule)
                .boxed()
                .toList());
        assertEquals(List.of(0, 1, 257, 0, 0, 0, 4, 2, 258, 2, 258, 3, 5),
                java.util.stream.IntStream
                .range(0, RealtimeRayTracingPipeline.RAYGEN_GROUP_COUNT)
                .map(RealtimeRayTracingPipeline::raygenControl)
                .boxed()
                .toList());
        assertEquals(List.of(0, 1, 1, 2, 2, 3), java.util.stream.IntStream
                .range(0, OfflineRayTracingPipeline.RAYGEN_GROUP_COUNT)
                .map(OfflineRayTracingPipeline::raygenModule)
                .boxed()
                .toList());
        assertEquals(List.of(0, 1, 257, 2, 258, 4), java.util.stream.IntStream
                .range(0, OfflineRayTracingPipeline.RAYGEN_GROUP_COUNT)
                .map(OfflineRayTracingPipeline::raygenControl)
                .boxed()
                .toList());
    }

    @Test
    void realtimeAndSharcSchedulesShareEveryUnchangedModule() {
        RaygenSchedule realtime = RealtimeWavefrontGroups.standardSchedule("_ser.rgen.spv");
        RaygenSchedule sharc = RealtimeWavefrontGroups.sharcSchedule("_ser.rgen.spv");
        assertEquals(RealtimeWavefrontGroups.MODULE_COUNT, realtime.moduleCount());
        assertEquals(RealtimeWavefrontGroups.GROUP_COUNT, realtime.groupCount());
        assertEquals(realtime.moduleCount(), sharc.moduleCount());
        assertEquals(realtime.groupCount(), sharc.groupCount());
        for (int module = 0; module < realtime.moduleCount(); module++) {
            if (module != 5 && module != 6) {
                assertEquals(realtime.moduleResource(module), sharc.moduleResource(module));
            }
        }
        assertEquals(
                "/prime/shaders/realtime_wavefront_sharc_light_ser.rgen.spv",
                sharc.moduleResource(5));
        assertEquals(
                "/prime/shaders/realtime_wavefront_sharc_shade_ser.rgen.spv",
                sharc.moduleResource(6));
        for (int group = 0; group < realtime.groupCount(); group++) {
            assertEquals(realtime.module(group), sharc.module(group));
            assertEquals(realtime.control(group), sharc.control(group));
        }
    }

    @Test
    void raygenScheduleRejectsInvalidParallelMetadataAtItsBoundary() {
        assertThrows(IllegalArgumentException.class, () -> RaygenSchedule.of(
                List.of("module"), new int[] {0}, new int[0]));
        assertThrows(IllegalArgumentException.class, () -> RaygenSchedule.of(
                List.of("module"), new int[] {1}, new int[] {0}));
        assertThrows(IllegalArgumentException.class, () -> RaygenSchedule.single("", 0));
    }

    @Test
    void setOneAbiDoesNotCrossRendererBoundary() throws IOException {
        for (String suffix : List.of("", "_subgroup", "_ser")) {
            Set<Integer> realtime = descriptorBindings(
                    wavefrontShaders(
                             "realtime",
                             suffix,
                             List.of("head", "step", "primary", "primary_area",
                                    "primary_sun", "light", "shade",
                                    "transparent_shade", "resolve",
                                    "transparent_resolve")),
                    1);
            assertTrue(realtime.contains(ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS));
            assertTrue(realtime.contains(ShaderAbi.DESCRIPTOR_WAVEFRONT_QUEUE));
            assertTrue(realtime.contains(ShaderAbi.DESCRIPTOR_STABLE_RADIANCE));
            assertFalse(realtime.contains(ShaderAbi.OFFLINE_DESCRIPTOR_RUNNING_MEAN));
            assertFalse(realtime.contains(ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_PATHS));
            assertFalse(realtime.contains(ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_QUEUE));

            Set<Integer> offline = descriptorBindings(
                    wavefrontShaders(
                            "offline",
                            suffix,
                            List.of("head", "step", "area", "resolve")),
                    1);
            assertEquals(Set.of(
                    ShaderAbi.OFFLINE_DESCRIPTOR_RUNNING_MEAN,
                    ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_PATHS,
                    ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_QUEUE), offline);
        }
    }

    @Test
    void sharcModulesExposeOnlyTheDeclaredFrameBinding() throws IOException {
        for (String suffix : List.of("", "_subgroup", "_ser")) {
            Set<Integer> query = descriptorBindings(List.of(
                    "realtime_wavefront_sharc_light" + suffix + ".rgen.spv",
                    "realtime_wavefront_sharc_shade" + suffix + ".rgen.spv"), 1);
            assertTrue(query.contains(ShaderAbi.DESCRIPTOR_SHARC_FRAME));
            assertTrue(query.contains(ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS));
            assertTrue(query.contains(ShaderAbi.DESCRIPTOR_WAVEFRONT_QUEUE));
        }
        for (String shader : List.of(
                "sharc_integrated_update.comp.spv", "sharc_resolve.comp.spv")) {
            Set<Integer> bindings = descriptorBindings(List.of(shader), 1);
            assertTrue(bindings.contains(ShaderAbi.DESCRIPTOR_SHARC_FRAME));
            assertFalse(bindings.contains(ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS));
            assertFalse(bindings.contains(ShaderAbi.DESCRIPTOR_WAVEFRONT_QUEUE));
        }
    }

    @Test
    void optimizedModulesPreservePayloadAbi() throws IOException {
        String tracePayload = "struct(vec3(f32),f32,vec3(f32),"
                + "u32,u32,u32,f32,f32,u32,f32,u32,u32,vec3(f32),u32,vec3(f32),u32)";
        String shadowPayload = "struct(vec4(f32),vec4(f32),vec4(f32),vec4(f32),"
                + "u32,vec2(u32),vec2(u32))";
        for (String shader : List.of("world.rmiss.spv", "world.rchit.spv")) {
            assertEquals(
                    Set.of(tracePayload),
                    payloadShapes(shader, STORAGE_INCOMING_RAY_PAYLOAD));
        }
        for (String shader : List.of(
                "shadow.rmiss.spv", "shadow.rchit.spv", "shadow.rahit.spv")) {
            assertEquals(
                    Set.of(shadowPayload),
                    payloadShapes(shader, STORAGE_INCOMING_RAY_PAYLOAD));
        }
        for (String suffix : List.of("", "_subgroup", "_ser")) {
            assertEquals(
                    Set.of(tracePayload),
                    payloadShapes(
                            wavefrontShader("realtime", "head", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(tracePayload, shadowPayload),
                    payloadShapes(
                            wavefrontShader("realtime", "step", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(),
                    payloadShapes(
                            wavefrontShader("realtime", "primary", suffix),
                            STORAGE_RAY_PAYLOAD));
            for (String stage : List.of("primary_area", "primary_sun")) {
                assertEquals(
                        Set.of(shadowPayload),
                        payloadShapes(
                                wavefrontShader("realtime", stage, suffix),
                                STORAGE_RAY_PAYLOAD));
            }
            assertEquals(
                    Set.of(),
                    payloadShapes(
                            wavefrontShader("realtime", "resolve", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(tracePayload, shadowPayload),
                    payloadShapes(
                            wavefrontShader("offline", "head", suffix),
                            STORAGE_RAY_PAYLOAD));
            for (String stage : List.of("step")) {
                Set<String> payloads = payloadShapes(
                        wavefrontShader("offline", stage, suffix),
                        STORAGE_RAY_PAYLOAD);
                assertTrue(payloads.contains(tracePayload), "offline " + stage + suffix);
                assertTrue(payloads.contains(shadowPayload), "offline " + stage + suffix);
            }
            assertEquals(
                    Set.of(tracePayload),
                    payloadShapes(
                            wavefrontShader("realtime", "transparent_resolve", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(),
                    payloadShapes(
                            wavefrontShader("realtime", "shade", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(),
                    payloadShapes(
                            wavefrontShader("realtime", "transparent_shade", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(shadowPayload),
                    payloadShapes(
                            wavefrontShader("offline", "area", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(shadowPayload),
                    payloadShapes(
                            wavefrontShader("realtime", "light", suffix),
                            STORAGE_RAY_PAYLOAD));
        }
    }

    @Test
    void serHeadPublishesWorkWithoutSubgroupCollectives() throws IOException {
        Set<Integer> head = parse(
                wavefrontShader("realtime", "head", "_ser")).opcodes;
        assertFalse(head.contains(OP_GROUP_NON_UNIFORM_ELECT));
        assertFalse(head.contains(OP_GROUP_NON_UNIFORM_BROADCAST_FIRST));
        assertFalse(head.contains(OP_GROUP_NON_UNIFORM_BALLOT));
        assertFalse(head.contains(OP_GROUP_NON_UNIFORM_BALLOT_BIT_COUNT));

        Set<Integer> primary = parse(
                wavefrontShader("realtime", "primary", "_ser")).opcodes;
        assertTrue(primary.contains(OP_GROUP_NON_UNIFORM_ELECT));
        assertTrue(primary.contains(OP_GROUP_NON_UNIFORM_BROADCAST_FIRST));
        assertTrue(primary.contains(OP_GROUP_NON_UNIFORM_BALLOT));
        assertTrue(primary.contains(OP_GROUP_NON_UNIFORM_BALLOT_BIT_COUNT));
    }

    @Test
    void realtimeStepUsesSubgroupShadeQueueCompactionWhenAvailable() throws IOException {
        Set<Integer> scalar = parse(
                wavefrontShader("realtime", "step", "")).opcodes;
        assertFalse(scalar.contains(OP_GROUP_NON_UNIFORM_ELECT));
        assertFalse(scalar.contains(OP_GROUP_NON_UNIFORM_BROADCAST_FIRST));
        assertFalse(scalar.contains(OP_GROUP_NON_UNIFORM_BALLOT));
        assertFalse(scalar.contains(OP_GROUP_NON_UNIFORM_BALLOT_BIT_COUNT));

        for (String suffix : List.of("_subgroup", "_ser")) {
            Set<Integer> step = parse(
                    wavefrontShader("realtime", "step", suffix)).opcodes;
            assertTrue(step.contains(OP_GROUP_NON_UNIFORM_ELECT), suffix);
            assertTrue(step.contains(OP_GROUP_NON_UNIFORM_BROADCAST_FIRST), suffix);
            assertTrue(step.contains(OP_GROUP_NON_UNIFORM_BALLOT), suffix);
            assertTrue(step.contains(OP_GROUP_NON_UNIFORM_BALLOT_BIT_COUNT), suffix);
        }
    }

    @Test
    void wavefrontBackingHasDeclaredFourKSize() {
        assertEquals(3_981_312_096L,
                RealtimeRayTracingPipeline.wavefrontBytes(3840, 2160));
        assertEquals(1_526_169_632L,
                OfflineRayTracingPipeline.wavefrontBytes(3840, 2160));
        assertEquals(
                1455.4687805175781,
                OfflineRayTracingPipeline.wavefrontBytes(3840, 2160)
                        / (1024.0 * 1024.0));
        assertThrows(
                IllegalArgumentException.class,
                () -> OfflineRayTracingPipeline.wavefrontBytes(0, 2160));
        assertThrows(
                ArithmeticException.class,
                () -> RealtimeRayTracingPipeline.wavefrontBytes(
                        Integer.MAX_VALUE, Integer.MAX_VALUE));
        RealtimeRayTracingPipeline.validateRanges(3840, 2160, 0xffff_ffffL);
        OfflineRayTracingPipeline.validateRanges(3840, 2160, 0xffff_ffffL);
        assertThrows(
                IllegalStateException.class,
                () -> RealtimeRayTracingPipeline.validateDispatch(
                        3840, 2160, 3840 * 2160));
        RealtimeRayTracingPipeline.validateDispatch(3840, 2160, 2 * 3840 * 2160);
        OfflineRayTracingPipeline.validateDispatch(3840, 2160, 1 << 24);
    }

    @Test
    void deferredCompilationClampsDriverConcurrencyToTheHost() {
        assertEquals(1, TraceProgram.deferredWorkerCount(0, 32));
        assertEquals(2, TraceProgram.deferredWorkerCount(2, 32));
        assertEquals(8, TraceProgram.deferredWorkerCount(32, 8));
        assertEquals(32, TraceProgram.deferredWorkerCount(-1, 32));
        assertEquals(1, TraceProgram.deferredWorkerCount(8, 0));
    }

    @Test
    void compiledPathRecordsUseIndependentStrides() throws IOException {
        for (String suffix : List.of("", "_subgroup", "_ser")) {
            assertRecordStride(
                    wavefrontShader("realtime", "step", suffix),
                    ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS,
                    ShaderAbi.WAVEFRONT_PATH_RECORD_SIZE);
            assertRecordStride(
                    wavefrontShader("offline", "step", suffix),
                    ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_PATHS,
                    ShaderAbi.OFFLINE_WAVEFRONT_PATH_RECORD_SIZE);
        }
    }

    private static List<String> wavefrontShaders(
            String renderer, String suffix, List<String> stages) {
        return stages.stream()
                .map(stage -> wavefrontShader(renderer, stage, suffix))
                .toList();
    }

    private static String wavefrontShader(
            String renderer, String stage, String suffix) {
        return renderer + "_wavefront_" + stage + suffix + ".rgen.spv";
    }

    private static void assertRecordStride(
            String shader, int binding, int expectedStride) throws IOException {
        Spirv module = parse(shader);
        Variable paths = module.variables.stream()
                .filter(variable -> variable.storageClass == STORAGE_BUFFER)
                .filter(variable -> module.bindings.getOrDefault(variable.identifier, -1)
                        == binding)
                .filter(variable -> module.sets.getOrDefault(variable.identifier, -1) == 1)
                .findFirst()
                .orElseThrow();
        Type pointer = module.requireType(paths.type);
        Type block = module.requireType(pointer.operands[1]);
        int arrayIdentifier = block.operands[0];
        assertEquals(expectedStride, module.arrayStrides.get(arrayIdentifier));
    }

    private static Set<Integer> descriptorBindings(
            List<String> shaders, int descriptorSet) throws IOException {
        Set<Integer> result = new HashSet<>();
        for (String shader : shaders) {
            Spirv module = parse(shader);
            for (Variable variable : module.variables) {
                if (module.sets.getOrDefault(variable.identifier, -1) == descriptorSet) {
                    Integer binding = module.bindings.get(variable.identifier);
                    if (binding != null) {
                        result.add(binding);
                    }
                }
            }
        }
        return result;
    }

    private static Set<String> payloadShapes(String shader, int storageClass)
            throws IOException {
        Spirv module = parse(shader);
        Set<String> result = new HashSet<>();
        for (Variable variable : module.variables) {
            if (variable.storageClass != storageClass) {
                continue;
            }
            Type pointer = module.requireType(variable.type);
            result.add(typeShape(module, pointer.operands[1]));
        }
        return result;
    }

    private static Spirv parse(String shader) throws IOException {
        int[] words = spirvWords(shader);
        Spirv result = new Spirv();
        for (int offset = 5; offset < words.length; ) {
            int instruction = words[offset];
            int wordCount = instruction >>> 16;
            int opcode = instruction & 0xffff;
            if (wordCount <= 0 || offset + wordCount > words.length) {
                throw new IllegalArgumentException("Malformed SPIR-V instruction");
            }
            result.opcodes.add(opcode);
            result.opcodeCounts.merge(opcode, 1, Integer::sum);
            if (opcode == OP_TYPE_INT
                    || opcode == OP_TYPE_FLOAT
                    || opcode == OP_TYPE_VECTOR
                    || opcode == OP_TYPE_RUNTIME_ARRAY
                    || opcode == OP_TYPE_STRUCT
                    || opcode == OP_TYPE_POINTER) {
                result.types.put(
                        words[offset + 1],
                        new Type(opcode, Arrays.copyOfRange(
                                words, offset + 2, offset + wordCount)));
            } else if (opcode == OP_VARIABLE) {
                result.variables.add(new Variable(
                        words[offset + 1], words[offset + 2], words[offset + 3]));
            } else if (opcode == OP_DECORATE && wordCount >= 4) {
                int target = words[offset + 1];
                switch (words[offset + 2]) {
                    case DECORATION_ARRAY_STRIDE ->
                            result.arrayStrides.put(target, words[offset + 3]);
                    case DECORATION_BINDING ->
                            result.bindings.put(target, words[offset + 3]);
                    case DECORATION_DESCRIPTOR_SET ->
                            result.sets.put(target, words[offset + 3]);
                    default -> { }
                }
            }
            offset += wordCount;
        }
        return result;
    }

    private static int[] spirvWords(String shader) throws IOException {
        String resource = "/prime/shaders/" + shader;
        byte[] bytes;
        try (InputStream input = TracePipelinesContractTest.class
                .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing compiled shader " + resource);
            }
            bytes = input.readAllBytes();
        }
        int[] words = new int[bytes.length / Integer.BYTES];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(words);
        if (words.length < 5 || words[0] != 0x0723_0203) {
            throw new IllegalArgumentException("Malformed SPIR-V header");
        }
        return words;
    }

    private static String typeShape(Spirv module, int identifier) {
        Type type = module.requireType(identifier);
        return switch (type.opcode) {
            case OP_TYPE_INT -> (type.operands[1] == 0 ? "u" : "i") + type.operands[0];
            case OP_TYPE_FLOAT -> "f" + type.operands[0];
            case OP_TYPE_VECTOR -> "vec" + type.operands[1]
                    + "(" + typeShape(module, type.operands[0]) + ")";
            case OP_TYPE_STRUCT -> "struct("
                    + Arrays.stream(type.operands)
                            .mapToObj(member -> typeShape(module, member))
                            .reduce((left, right) -> left + "," + right)
                            .orElse("")
                    + ")";
            default -> throw new IllegalArgumentException(
                    "Unsupported SPIR-V type " + type.opcode);
        };
    }

    private static final class Spirv {
        final Map<Integer, Type> types = new HashMap<>();
        final Map<Integer, Integer> bindings = new HashMap<>();
        final Map<Integer, Integer> sets = new HashMap<>();
        final Map<Integer, Integer> arrayStrides = new HashMap<>();
        final List<Variable> variables = new ArrayList<>();
        final Set<Integer> opcodes = new HashSet<>();
        final Map<Integer, Integer> opcodeCounts = new HashMap<>();

        int opcodeCount(int opcode) {
            return this.opcodeCounts.getOrDefault(opcode, 0);
        }

        Type requireType(int identifier) {
            Type type = this.types.get(identifier);
            if (type == null) {
                throw new IllegalArgumentException("Missing SPIR-V type " + identifier);
            }
            return type;
        }
    }

    private record Type(int opcode, int[] operands) { }
    private record Variable(int type, int identifier, int storageClass) { }
}
