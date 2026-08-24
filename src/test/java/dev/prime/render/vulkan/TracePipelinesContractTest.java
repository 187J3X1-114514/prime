package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
        assertEquals(14, RealtimeRayTracingPipeline.RAYGEN_GROUP_COUNT);
        assertEquals(14, RealtimeRayTracingPipeline.RAYGEN_MODULE_COUNT);
        assertEquals(58, RealtimeRayTracingPipeline.dispatchCount(12));
        assertEquals(14, RealtimeRayTracingPipeline.dispatchCount(1));
        assertEquals(266, RealtimeRayTracingPipeline.dispatchCount(64));
        assertEquals(25, RealtimeRayTracingPipeline.DESCRIPTOR_BINDING_COUNT);

        assertEquals(6, OfflineRayTracingPipeline.RAYGEN_GROUP_COUNT);
        assertEquals(4, OfflineRayTracingPipeline.RAYGEN_MODULE_COUNT);
        assertEquals(25, OfflineRayTracingPipeline.dispatchCount(12));
        assertEquals(3, OfflineRayTracingPipeline.dispatchCount(1));
        assertEquals(129, OfflineRayTracingPipeline.dispatchCount(64));
        assertEquals(3, OfflineRayTracingPipeline.DESCRIPTOR_BINDING_COUNT);

        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13),
                java.util.stream.IntStream
                .range(0, RealtimeRayTracingPipeline.RAYGEN_GROUP_COUNT)
                .map(RealtimeRayTracingPipeline::raygenModule)
                .boxed()
                .toList());
        assertEquals(List.of(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
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
    void realtimeBarriersExposeOnlyTheNextStageImageDependencies() {
        assertArrayEquals(
                new int[] {1, 2},
                RealtimeRayTracingPipeline.primaryDirectInputImageIndices());
        assertArrayEquals(
                new int[] {0, 1, 2, 4, 6, 7, 8, 9, 10, 20, 21},
                RealtimeRayTracingPipeline.primaryInputImageIndices());
        assertArrayEquals(
                new int[] {
                    0, 1, 2, 4, 5, 6, 7, 8, 9, 10,
                    11, 12, 13, 14, 15, 16, 17, 18, 21
                },
                RealtimeRayTracingPipeline.nextStepInputImageIndices());
        assertTrue(RealtimeRayTracingPipeline.standardBarrierPublishesImagesBefore(
                RealtimeWavefrontGroups.PRIMARY_CHAIN));
        assertTrue(RealtimeRayTracingPipeline.standardBarrierPublishesImagesBefore(
                RealtimeWavefrontGroups.PRIMARY_LANDING_SECONDARY));
        assertTrue(RealtimeRayTracingPipeline.standardBarrierPublishesImagesBefore(
                RealtimeWavefrontGroups.PRIMARY_LANDING_ADVANCE));
        assertTrue(RealtimeRayTracingPipeline.standardBarrierPublishesImagesBefore(
                RealtimeWavefrontGroups.NONE));
        assertFalse(RealtimeRayTracingPipeline.standardBarrierPublishesImagesBefore(
                RealtimeWavefrontGroups.PRIMARY_LANDING_CLASSIFY));
        assertFalse(RealtimeRayTracingPipeline.standardBarrierPublishesImagesBefore(
                RealtimeWavefrontGroups.CLASSIFY));
    }

    @Test
    void sharcUsesIndependentQueryAndTrainingSchedules() {
        RaygenSchedule realtime = RealtimeWavefrontGroups.standardSchedule("_ser.rgen.spv");
        RaygenSchedule query = SharcWavefrontGroups.querySchedule("_ser.rgen.spv");
        RaygenSchedule training = SharcWavefrontGroups.trainingSchedule("_ser.rgen.spv");
        assertEquals(RealtimeWavefrontGroups.MODULE_COUNT, realtime.moduleCount());
        assertEquals(RealtimeWavefrontGroups.GROUP_COUNT, realtime.groupCount());
        assertEquals(12, query.moduleCount());
        assertEquals(12, query.groupCount());
        assertEquals(8, training.moduleCount());
        assertEquals(9, training.groupCount());
        assertEquals(
                "/prime/shaders/realtime_wavefront_two_stage_primary_ser.rgen.spv",
                realtime.moduleResource(2));
        assertEquals(
                "/prime/shaders/realtime_wavefront_steady_area_ser.rgen.spv",
                realtime.moduleResource(11));
        assertEquals(
                "/prime/shaders/realtime_sharc_bridge_trace_ser.rgen.spv",
                query.moduleResource(8));
        assertEquals(
                "/prime/shaders/realtime_sharc_bridge_query_ser.rgen.spv",
                query.moduleResource(9));
        assertEquals(
                "/prime/shaders/realtime_sharc_training_camera_ser.rgen.spv",
                training.moduleResource(0));
        assertEquals(
                "/prime/shaders/realtime_sharc_training_walk_ser.rgen.spv",
                training.moduleResource(2));
        assertEquals(
                "/prime/shaders/realtime_sharc_training_reduce_ser.rgen.spv",
                training.moduleResource(7));
        assertEquals(
                List.of(0, 1, 2, 2, 3, 4, 5, 6, 7),
                java.util.stream.IntStream.range(0, training.groupCount())
                        .map(training::module)
                        .boxed()
                        .toList());
        assertEquals(
                List.of(0, 0, 0, 1, 0, 0, 0, 0, 0),
                java.util.stream.IntStream.range(0, training.groupCount())
                        .map(training::control)
                        .boxed()
                        .toList());
        assertEquals(12, RealtimeRayTracingPipeline.SharcRayTracingPipeline
                .queryDispatchCount());
        assertEquals(8, RealtimeRayTracingPipeline.SharcRayTracingPipeline
                .trainingDispatchCount(8));
        assertEquals(22, RealtimeRayTracingPipeline.SharcRayTracingPipeline
                .dispatchCount(8));
        assertEquals(14, RealtimeRayTracingPipeline.SharcRayTracingPipeline
                .trainingDispatchCount(12));
        assertEquals(28, RealtimeRayTracingPipeline.SharcRayTracingPipeline
                .dispatchCount(12));
        assertEquals(8, RealtimeRayTracingPipeline.SharcRayTracingPipeline
                .trainingWalkDepth(1920, 1080));
        assertEquals(1, RealtimeRayTracingPipeline.SharcRayTracingPipeline
                .trainingWalkDepth(1, 1));
        assertEquals(50, RealtimeRayTracingPipeline.SharcRayTracingPipeline
                .trainingDispatchCount(8, 1, 1));
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
    void imageDiagnosticsUseOneIsolatedSourceAndTargetLayout() throws IOException {
        assertEquals(
                Set.of(0, 1),
                descriptorBindings(List.of("image_diagnostic_rgba8.comp.spv"), 0));
        assertEquals(
                Set.of(0, 1),
                descriptorBindings(List.of("image_diagnostic_rgba16.comp.spv"), 0));
    }

    @Test
    void setOneAbiDoesNotCrossRendererBoundary() throws IOException {
        for (String suffix : List.of("", "_ser")) {
            Set<Integer> realtime = descriptorBindings(
                    List.of(
                            wavefrontShader("realtime", "head", suffix),
                            wavefrontShader("realtime", "two_stage_primary", suffix),
                            wavefrontShader("realtime", "primary_chain", suffix),
                            wavefrontShader("realtime", "primary_landing", suffix),
                            wavefrontShader(
                                    "realtime", "primary_landing_primary", suffix),
                            wavefrontShader(
                                    "realtime", "primary_landing_secondary", suffix),
                            wavefrontShader(
                                    "realtime", "primary_landing_advance", suffix),
                            wavefrontShader("realtime", "steady_classify", suffix),
                            wavefrontShader("realtime", "steady_none", suffix),
                            wavefrontShader("realtime", "steady_sun", suffix),
                            wavefrontShader("realtime", "steady_area", suffix),
                            wavefrontShader(
                                    "realtime", "two_stage_transparent_resolve", suffix),
                            wavefrontShader("realtime", "primary_direct", suffix),
                            wavefrontShader("realtime", "resolve", suffix)),
                    1);
            assertTrue(realtime.contains(ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS));
            assertTrue(realtime.contains(ShaderAbi.DESCRIPTOR_WAVEFRONT_QUEUE));
            assertTrue(realtime.contains(ShaderAbi.DESCRIPTOR_STABLE_RADIANCE));
            assertFalse(realtime.contains(ShaderAbi.DESCRIPTOR_SHARC_FRAME));
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
        for (String suffix : List.of("", "_ser")) {
            Set<Integer> sharc = descriptorBindings(List.of(
                    sharcShader("bridge_query", suffix),
                    sharcShader("training_camera", suffix),
                    sharcShader("training_walk", suffix),
                    sharcShader("training_classify", suffix),
                    sharcShader("training_none", suffix),
                    sharcShader("training_sun", suffix),
                    sharcShader("training_area", suffix),
                    sharcShader("training_reduce", suffix)), 1);
            assertTrue(sharc.contains(ShaderAbi.DESCRIPTOR_SHARC_FRAME));
            assertTrue(sharc.contains(ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS));
            assertTrue(sharc.contains(ShaderAbi.DESCRIPTOR_WAVEFRONT_QUEUE));

            Set<Integer> narrow = descriptorBindings(List.of(
                    sharcShader("bridge_trace", suffix),
                    sharcShader("training_landing", suffix)), 1);
            assertFalse(narrow.contains(ShaderAbi.DESCRIPTOR_SHARC_FRAME));
            assertTrue(narrow.contains(ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS));
            assertTrue(narrow.contains(ShaderAbi.DESCRIPTOR_WAVEFRONT_QUEUE));
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
        for (String suffix : List.of("", "_ser")) {
            assertEquals(
                    Set.of(tracePayload),
                    payloadShapes(
                            wavefrontShader("realtime", "head", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(tracePayload),
                    payloadShapes(
                            wavefrontShader("realtime", "primary_chain", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(),
                    payloadShapes(
                            wavefrontShader("realtime", "primary_landing", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(shadowPayload),
                    payloadShapes(
                            wavefrontShader(
                                    "realtime", "primary_landing_primary", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(shadowPayload),
                    payloadShapes(
                            wavefrontShader(
                                    "realtime", "primary_landing_secondary", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(),
                    payloadShapes(
                            wavefrontShader(
                                    "realtime", "primary_landing_advance", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(tracePayload),
                    payloadShapes(
                            wavefrontShader("realtime", "steady_classify", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(),
                    payloadShapes(
                            wavefrontShader("realtime", "steady_none", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(shadowPayload),
                    payloadShapes(
                            wavefrontShader("realtime", "steady_sun", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(shadowPayload),
                    payloadShapes(
                            wavefrontShader("realtime", "steady_area", suffix),
                            STORAGE_RAY_PAYLOAD));
            for (String stage : List.of(
                    "training_camera", "training_walk", "bridge_trace")) {
                assertEquals(
                        Set.of(tracePayload),
                        payloadShapes(sharcShader(stage, suffix), STORAGE_RAY_PAYLOAD));
            }
            for (String stage : List.of(
                    "bridge_query",
                    "training_landing",
                    "training_classify",
                    "training_none",
                    "training_reduce")) {
                assertEquals(
                        Set.of(),
                        payloadShapes(sharcShader(stage, suffix), STORAGE_RAY_PAYLOAD));
            }
            for (String stage : List.of("training_sun", "training_area")) {
                assertEquals(
                        Set.of(shadowPayload),
                        payloadShapes(sharcShader(stage, suffix), STORAGE_RAY_PAYLOAD));
            }
            assertEquals(
                    Set.of(shadowPayload),
                    payloadShapes(
                            wavefrontShader("realtime", "primary_direct", suffix),
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
                    Set.of(),
                    payloadShapes(
                            wavefrontShader(
                                    "realtime", "two_stage_transparent_resolve", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(),
                    payloadShapes(
                            wavefrontShader("realtime", "two_stage_primary", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(),
                    payloadShapes(
                            wavefrontShader("realtime", "resolve", suffix),
                            STORAGE_RAY_PAYLOAD));
            assertEquals(
                    Set.of(shadowPayload),
                    payloadShapes(
                            wavefrontShader("offline", "area", suffix),
                            STORAGE_RAY_PAYLOAD));
        }
    }

    @Test
    void serReorderedPublishersDoNotReuseProducerSubgroups() throws IOException {
        Set<Integer> head = parse(
                wavefrontShader("realtime", "head", "_ser")).opcodes;
        assertFalse(head.contains(OP_GROUP_NON_UNIFORM_ELECT));
        assertFalse(head.contains(OP_GROUP_NON_UNIFORM_BROADCAST_FIRST));
        assertFalse(head.contains(OP_GROUP_NON_UNIFORM_BALLOT));
        assertFalse(head.contains(OP_GROUP_NON_UNIFORM_BALLOT_BIT_COUNT));

        Set<Integer> trainingWalk = parse(
                sharcShader("training_walk", "_ser")).opcodes;
        assertFalse(trainingWalk.contains(OP_GROUP_NON_UNIFORM_ELECT));
        assertFalse(trainingWalk.contains(OP_GROUP_NON_UNIFORM_BROADCAST_FIRST));
        assertFalse(trainingWalk.contains(OP_GROUP_NON_UNIFORM_BALLOT));
        assertFalse(trainingWalk.contains(OP_GROUP_NON_UNIFORM_BALLOT_BIT_COUNT));

        Set<Integer> primary = parse(
                wavefrontShader("realtime", "two_stage_primary", "_ser")).opcodes;
        assertTrue(primary.contains(OP_GROUP_NON_UNIFORM_ELECT));
        assertTrue(primary.contains(OP_GROUP_NON_UNIFORM_BROADCAST_FIRST));
        assertTrue(primary.contains(OP_GROUP_NON_UNIFORM_BALLOT));
        assertTrue(primary.contains(OP_GROUP_NON_UNIFORM_BALLOT_BIT_COUNT));
    }

    @Test
    void laneVaryingQueuePartitionsUseScalarReservation()
            throws IOException {
        for (String suffix : List.of("", "_ser")) {
            for (String stage : List.of("primary_landing", "steady_classify")) {
                Set<Integer> opcodes = parse(
                        wavefrontShader("realtime", stage, suffix)).opcodes;
                assertFalse(opcodes.contains(OP_GROUP_NON_UNIFORM_ELECT),
                        stage + suffix);
                assertFalse(opcodes.contains(OP_GROUP_NON_UNIFORM_BROADCAST_FIRST),
                        stage + suffix);
                assertFalse(opcodes.contains(OP_GROUP_NON_UNIFORM_BALLOT),
                        stage + suffix);
                assertFalse(opcodes.contains(OP_GROUP_NON_UNIFORM_BALLOT_BIT_COUNT),
                        stage + suffix);
            }
            Set<Integer> training = parse(
                    sharcShader("training_classify", suffix)).opcodes;
            assertFalse(training.contains(OP_GROUP_NON_UNIFORM_ELECT));
            assertFalse(training.contains(OP_GROUP_NON_UNIFORM_BROADCAST_FIRST));
            assertFalse(training.contains(OP_GROUP_NON_UNIFORM_BALLOT));
            assertFalse(training.contains(OP_GROUP_NON_UNIFORM_BALLOT_BIT_COUNT));
        }
    }

    @Test
    void wavefrontBackingHasDeclaredFourKSize() {
        assertEquals(3_881_779_296L,
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
        for (String suffix : List.of("", "_ser")) {
            assertRecordStride(
                    wavefrontShader("realtime", "primary_chain", suffix),
                    ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS,
                    ShaderAbi.WAVEFRONT_PATH_RECORD_SIZE);
            assertRecordStride(
                    wavefrontShader("realtime", "primary_landing", suffix),
                    ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS,
                    ShaderAbi.WAVEFRONT_PATH_RECORD_SIZE);
            assertRecordStride(
                    wavefrontShader(
                            "realtime", "primary_landing_primary", suffix),
                    ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS,
                    ShaderAbi.WAVEFRONT_PATH_RECORD_SIZE);
            assertRecordStride(
                    wavefrontShader(
                            "realtime", "primary_landing_secondary", suffix),
                    ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS,
                    ShaderAbi.WAVEFRONT_PATH_RECORD_SIZE);
            assertRecordStride(
                    wavefrontShader(
                            "realtime", "primary_landing_advance", suffix),
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

    private static String sharcShader(String stage, String suffix) {
        return "realtime_sharc_" + stage + suffix + ".rgen.spv";
    }

    private static String wavefrontShader(
            String renderer, String stage, String suffix) {
        if ("_ser".equals(suffix)
                && ("resolve".equals(stage)
                        || ("realtime".equals(renderer) && "primary_direct".equals(stage)))) {
            suffix = "";
        }
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
