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
    private static final int DECORATION_ARRAY_STRIDE = 6;
    private static final int DECORATION_BINDING = 33;
    private static final int DECORATION_DESCRIPTOR_SET = 34;
    private static final int DECORATION_OFFSET = 35;
    private static final int STORAGE_BUFFER = 12;
    private static final int STORAGE_RAY_PAYLOAD = 5338;
    private static final int STORAGE_INCOMING_RAY_PAYLOAD = 5342;

    @Test
    void realtimeAndOfflineHaveIndependentSchedulesAndDescriptors() {
        assertEquals(8, RealtimeRayTracingPipeline.RAYGEN_GROUP_COUNT);
        assertEquals(5, RealtimeRayTracingPipeline.RAYGEN_MODULE_COUNT);
        assertEquals(27, RealtimeRayTracingPipeline.DISPATCH_COUNT);
        assertEquals(25, RealtimeRayTracingPipeline.DESCRIPTOR_BINDING_COUNT);

        assertEquals(4, PerformanceRayTracingPipeline.RAYGEN_GROUP_COUNT);
        assertEquals(3, PerformanceRayTracingPipeline.RAYGEN_MODULE_COUNT);
        assertEquals(9, PerformanceRayTracingPipeline.MAXIMUM_DISPATCH_COUNT);
        assertEquals(0, RealtimeRayTracingPipeline.performanceRounds(1));
        assertEquals(3, RealtimeRayTracingPipeline.performanceRounds(4));
        assertEquals(7, RealtimeRayTracingPipeline.performanceRounds(8));
        assertEquals(2, RealtimeRayTracingPipeline.performanceRounds(1) + 2);
        assertEquals(5, RealtimeRayTracingPipeline.performanceRounds(4) + 2);
        assertEquals(9, RealtimeRayTracingPipeline.performanceRounds(8) + 2);
        assertThrows(
                IllegalArgumentException.class,
                () -> RealtimeRayTracingPipeline.performanceRounds(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> RealtimeRayTracingPipeline.performanceRounds(9));

        assertEquals(8, OfflineRayTracingPipeline.RAYGEN_GROUP_COUNT);
        assertEquals(5, OfflineRayTracingPipeline.RAYGEN_MODULE_COUNT);
        assertEquals(27, OfflineRayTracingPipeline.DISPATCH_COUNT);
        assertEquals(3, OfflineRayTracingPipeline.DESCRIPTOR_BINDING_COUNT);

        assertEquals(List.of(0, 1, 1, 2, 2, 3, 3, 4),
                java.util.stream.IntStream
                .range(0, RealtimeRayTracingPipeline.RAYGEN_GROUP_COUNT)
                .map(RealtimeRayTracingPipeline::raygenModule)
                .boxed()
                .toList());
        assertEquals(List.of(0, 1, 257, 4, 260, 2, 258, 3),
                java.util.stream.IntStream
                .range(0, RealtimeRayTracingPipeline.RAYGEN_GROUP_COUNT)
                .map(RealtimeRayTracingPipeline::raygenControl)
                .boxed()
                .toList());
        assertEquals(List.of(0, 1, 1, 2),
                java.util.stream.IntStream
                .range(0, PerformanceRayTracingPipeline.RAYGEN_GROUP_COUNT)
                .map(PerformanceRayTracingPipeline::raygenModule)
                .boxed()
                .toList());
        assertEquals(List.of(0, 1, 257, 2),
                java.util.stream.IntStream
                .range(0, PerformanceRayTracingPipeline.RAYGEN_GROUP_COUNT)
                .map(PerformanceRayTracingPipeline::raygenControl)
                .boxed()
                .toList());
        assertEquals(List.of(0, 1, 1, 2, 2, 3, 3, 4), java.util.stream.IntStream
                .range(0, OfflineRayTracingPipeline.RAYGEN_GROUP_COUNT)
                .map(OfflineRayTracingPipeline::raygenModule)
                .boxed()
                .toList());
        assertEquals(List.of(0, 1, 257, 2, 258, 3, 259, 4), java.util.stream.IntStream
                .range(0, OfflineRayTracingPipeline.RAYGEN_GROUP_COUNT)
                .map(OfflineRayTracingPipeline::raygenControl)
                .boxed()
                .toList());
    }

    @Test
    void setOneAbiDoesNotCrossRendererBoundary() throws IOException {
        for (String suffix : List.of("", "_ser")) {
            Set<Integer> realtime = descriptorBindings(
                    wavefrontShaders(
                            "realtime",
                            suffix,
                            List.of("head", "step", "area", "tail", "resolve")),
                    1);
            assertTrue(realtime.contains(ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS));
            assertTrue(realtime.contains(ShaderAbi.DESCRIPTOR_WAVEFRONT_QUEUE));
            assertTrue(realtime.contains(ShaderAbi.DESCRIPTOR_STABLE_RADIANCE));
            assertFalse(realtime.contains(ShaderAbi.OFFLINE_DESCRIPTOR_RUNNING_MEAN));
            assertFalse(realtime.contains(ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_PATHS));
            assertFalse(realtime.contains(ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_QUEUE));

            Set<Integer> lightweight = descriptorBindings(
                    wavefrontShaders(
                            "lightweight",
                            suffix,
                            List.of("head", "step", "resolve")),
                    1);
            HashSet<Integer> lightweightExpected = new HashSet<>(realtime);
            lightweightExpected.remove(
                    ShaderAbi.DESCRIPTOR_WAVEFRONT_TRANSPORT_METADATA);
            assertEquals(lightweightExpected, lightweight);

            Set<Integer> offline = descriptorBindings(
                    wavefrontShaders(
                            "offline",
                            suffix,
                            List.of("head", "step", "area", "tail", "resolve")),
                    1);
            assertEquals(Set.of(
                    ShaderAbi.OFFLINE_DESCRIPTOR_RUNNING_MEAN,
                    ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_PATHS,
                    ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_QUEUE), offline);
        }
    }

    @Test
    void optimizedModulesPreservePayloadAbi() throws IOException {
        String tracePayload = "struct(vec3(f32),f32,vec3(f32),"
                + "u32,u32,u32,u32,u32,u32,u32,u32,u32)";
        String shadowPayload = "struct(vec4(f32),vec4(f32),i32)";
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
        for (String suffix : List.of("", "_ser")) {
            for (String prefix : List.of("realtime", "offline")) {
                for (String stage : List.of("head", "step", "tail")) {
                    Set<String> payloads = payloadShapes(
                            wavefrontShader(prefix, stage, suffix),
                            STORAGE_RAY_PAYLOAD);
                    assertTrue(payloads.contains(tracePayload), prefix + " " + stage + suffix);
                    assertTrue(payloads.contains(shadowPayload), prefix + " " + stage + suffix);
                }
            }
            for (String stage : List.of("head", "step")) {
                Set<String> payloads = payloadShapes(
                        wavefrontShader("lightweight", stage, suffix),
                        STORAGE_RAY_PAYLOAD);
                assertTrue(payloads.contains(tracePayload), "lightweight " + stage + suffix);
                assertTrue(payloads.contains(shadowPayload), "lightweight " + stage + suffix);
            }
            assertEquals(
                    Set.of(tracePayload),
                    payloadShapes(
                            wavefrontShader("realtime", "resolve", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(shadowPayload),
                    payloadShapes(
                            wavefrontShader("offline", "area", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(shadowPayload),
                    payloadShapes(
                            wavefrontShader("realtime", "area", suffix),
                            STORAGE_RAY_PAYLOAD));
        }
    }

    @Test
    void wavefrontBackingHasDeclaredFourKSize() {
        assertEquals(1_857_945_632L,
                RealtimeRayTracingPipeline.wavefrontBytes(3840, 2160));
        assertEquals(1_260_748_832L,
                OfflineRayTracingPipeline.wavefrontBytes(3840, 2160));
        assertEquals(729_907_232L,
                PerformanceRayTracingPipeline.wavefrontBytes(3840, 2160));
        assertEquals(182_476_832L,
                PerformanceRayTracingPipeline.wavefrontBytes(1920, 1080));
        assertEquals(
                1202.3437805175781,
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
        RealtimeRayTracingPipeline.validateDispatch(3840, 2160, 1 << 25);
        RealtimeRayTracingPipeline.validatePerformanceRanges(
                3840, 2160, 0xffff_ffffL);
        RealtimeRayTracingPipeline.validatePerformanceDispatch(
                3840, 2160, 1 << 24);
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
        for (String suffix : List.of("", "_ser")) {
            assertRecordStride(
                    wavefrontShader("realtime", "step", suffix),
                    ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS,
                    ShaderAbi.WAVEFRONT_PATH_RECORD_SIZE);
            assertRecordStride(
                    wavefrontShader("offline", "step", suffix),
                    ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_PATHS,
                    ShaderAbi.OFFLINE_WAVEFRONT_PATH_RECORD_SIZE);
            assertRecordStride(
                    wavefrontShader("lightweight", "step", suffix),
                    ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS,
                    ShaderAbi.LIGHTWEIGHT_WAVEFRONT_PATH_RECORD_SIZE);
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
