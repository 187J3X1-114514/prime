package dev.prime.render.replay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.AstronomySettings;
import dev.prime.render.AstronomyState;
import dev.prime.render.FrameCamera;
import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.LightingSettings;
import dev.prime.render.MaterialSettings;
import dev.prime.render.SunDirection;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.TransparentGuideMode;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.terrain.TerrainScene;
import dev.prime.render.vulkan.RayTracingPushConstants;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class RayTraceReplayInputCodecTest {
    @Test
    void semanticFrameRoundTripsAndRebindsDeviceAddresses() {
        Fixture fixture = input();
        IntegratorFrameInput original = fixture.input();
        RayTraceReplayInput captured =
                RayTraceReplayInput.capture(original, fixture.scene());
        byte[] encoded = RayTraceReplayInputCodec.encode(captured);
        RayTraceReplayInput decoded =
                RayTraceReplayInputCodec.decode(encoded);

        assertArrayEquals(
                encoded, RayTraceReplayInputCodec.encode(decoded));
        assertEquals(captured.camera(), decoded.camera());
        assertEquals(captured.astronomy(), decoded.astronomy());
        IntegratorFrameInput rebound = decoded.bind(fixture.scene());
        ByteBuffer expected = ByteBuffer.allocate(ShaderAbi.PUSH_CONSTANT_SIZE)
                .order(ByteOrder.nativeOrder());
        ByteBuffer actual = ByteBuffer.allocate(ShaderAbi.PUSH_CONSTANT_SIZE)
                .order(ByteOrder.nativeOrder());
        RayTracingPushConstants.write(
                original, fixture.scene(), expected);
        RayTracingPushConstants.write(
                rebound, fixture.scene(), actual);
        assertArrayEquals(expected.array(), actual.array());
    }

    @Test
    void allThreeProductModesKeepTheV4SizeAndWireCodes() {
        Fixture fixture = input();
        for (PostProcessingMode mode : PostProcessingMode.values()) {
            IntegratorFrameInput input = withMode(fixture.input(), mode);
            byte[] encoded = RayTraceReplayInputCodec.encode(
                    RayTraceReplayInput.capture(input, fixture.scene()));
            int expectedCode = switch (mode) {
                case NRD_FSR -> 0;
                case DLSS_RR -> 1;
                case DISABLED -> 2;
            };

            assertEquals(384, encoded.length);
            assertEquals(
                    expectedCode,
                    ByteBuffer.wrap(encoded)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .getInt(332));
            assertArrayEquals(
                    encoded,
                    RayTraceReplayInputCodec.encode(
                            RayTraceReplayInputCodec.decode(encoded)));
        }
    }

    @Test
    void allocatorAddressesAreNotSerializedButSceneIdentityIsChecked() {
        Fixture fixture = input();
        IntegratorFrameInput original = fixture.input();
        RayTraceReplayInput captured =
                RayTraceReplayInput.capture(original, fixture.scene());
        TerrainScene.ResidentSceneView relocated =
                new TerrainScene.ResidentSceneView(
                        99L,
                        0x1111_2222_3333_4444L,
                        fixture.scene().originX(),
                        fixture.scene().originY(),
                        fixture.scene().originZ(),
                        fixture.scene().revision(),
                        fixture.scene().resetRevision(),
                        fixture.scene().temporalRevision());

        IntegratorFrameInput rebound = captured.bind(relocated);

        assertEquals(original, rebound);
        captured.requireMatch(original, relocated);
        assertNotEquals(
                fixture.scene().sectionTableAddress(),
                relocated.sectionTableAddress());
        TerrainScene.ResidentSceneView wrongRevision =
                new TerrainScene.ResidentSceneView(
                        relocated.tlas(),
                        relocated.sectionTableAddress(),
                        relocated.originX(),
                        relocated.originY(),
                        relocated.originZ(),
                        relocated.revision() + 1L,
                        relocated.resetRevision(),
                        relocated.temporalRevision());
        assertThrows(
                IllegalArgumentException.class,
                () -> captured.bind(wrongRevision));
        assertThrows(
                IllegalArgumentException.class,
                () -> captured.requireMatch(
                        withSampleIndex(original, original.sampleIndex() + 1),
                        relocated));
    }

    @Test
    void decodeRejectsAStoredDerivedValueThatDisagreesWithCanonicalSteps() {
        Fixture fixture = input();
        byte[] encoded = RayTraceReplayInputCodec.encode(
                RayTraceReplayInput.capture(fixture.input(), fixture.scene()));
        int sceneBytes = 3 * Integer.BYTES + 3 * Long.BYTES;
        int frameWordsBeforeLighting = 13;
        int lightingStepWords = 3;
        int sunMultiplierOffset = 2 * Integer.BYTES
                + sceneBytes
                + FrameCameraSnapshot.ENCODED_BYTES
                + (frameWordsBeforeLighting + lightingStepWords)
                        * Integer.BYTES;
        ByteBuffer.wrap(encoded)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(sunMultiplierOffset, Float.floatToRawIntBits(1.0F));

        assertThrows(
                IllegalArgumentException.class,
                () -> RayTraceReplayInputCodec.decode(encoded));
    }

    @Test
    void decodeRejectsInvalidAstronomyAndFormerVersion() {
        Fixture fixture = input();
        byte[] latitude = RayTraceReplayInputCodec.encode(
                RayTraceReplayInput.capture(fixture.input(), fixture.scene()));
        int sceneBytes = 3 * Integer.BYTES + 3 * Long.BYTES;
        int latitudeOffset = 2 * Integer.BYTES
                + sceneBytes
                + FrameCameraSnapshot.ENCODED_BYTES
                + 5 * Integer.BYTES;
        ByteBuffer.wrap(latitude)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(latitudeOffset, 91);
        assertThrows(
                IllegalArgumentException.class,
                () -> RayTraceReplayInputCodec.decode(latitude));

        byte[] inconsistent = RayTraceReplayInputCodec.encode(
                RayTraceReplayInput.capture(fixture.input(), fixture.scene()));
        int sunOffset = 2 * Integer.BYTES
                + sceneBytes
                + FrameCameraSnapshot.ENCODED_BYTES
                + 2 * Integer.BYTES;
        ByteBuffer inconsistentBuffer = ByteBuffer.wrap(inconsistent)
                .order(ByteOrder.LITTLE_ENDIAN);
        inconsistentBuffer.putFloat(sunOffset, 0.0F);
        inconsistentBuffer.putFloat(sunOffset + Float.BYTES, 1.0F);
        inconsistentBuffer.putFloat(sunOffset + 2 * Float.BYTES, 0.0F);
        assertThrows(
                IllegalArgumentException.class,
                () -> RayTraceReplayInputCodec.decode(inconsistent));

        byte[] former = RayTraceReplayInputCodec.encode(
                RayTraceReplayInput.capture(fixture.input(), fixture.scene()));
        ByteBuffer.wrap(former)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(Integer.BYTES, 1);
        assertThrows(
                IllegalArgumentException.class,
                () -> RayTraceReplayInputCodec.decode(former));
    }

    @Test
    void decodeRejectsANonFiniteCamera() {
        Fixture fixture = input();
        byte[] encoded = RayTraceReplayInputCodec.encode(
                RayTraceReplayInput.capture(fixture.input(), fixture.scene()));
        int sceneBytes = 3 * Integer.BYTES + 3 * Long.BYTES;
        int cameraOffset = 2 * Integer.BYTES + sceneBytes;
        ByteBuffer.wrap(encoded)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(cameraOffset, Float.NaN);

        assertThrows(
                IllegalArgumentException.class,
                () -> RayTraceReplayInputCodec.decode(encoded));
    }

    private static IntegratorFrameInput withSampleIndex(
            IntegratorFrameInput input, int sampleIndex) {
        return new IntegratorFrameInput(
                input.camera(),
                input.width(),
                input.height(),
                input.astronomy(),
                input.packedRayCone(),
                sampleIndex,
                input.sampleEpoch(),
                input.jitterPhase(),
                input.cameraInWater(),
                input.postProcessingMode(),
                input.transparentGuideMode(),
                input.lighting(),
                input.material(),
                input.shInput(),
                input.rawNumericalDiagnostic(),
                input.triangleDebug());
    }

    private static IntegratorFrameInput withMode(
            IntegratorFrameInput input, PostProcessingMode mode) {
        TransparentGuideMode guide = switch (mode) {
            case NRD_FSR -> TransparentGuideMode.REFLECTION_AND_TRANSMISSION;
            case DLSS_RR -> TransparentGuideMode.TRANSMISSION_ONLY;
            case DISABLED -> TransparentGuideMode.DISABLED;
        };
        return new IntegratorFrameInput(
                input.camera(),
                input.width(),
                input.height(),
                input.astronomy(),
                input.packedRayCone(),
                input.sampleIndex(),
                input.sampleEpoch(),
                input.jitterPhase(),
                input.cameraInWater(),
                mode,
                guide,
                input.lighting(),
                input.material(),
                input.shInput(),
                input.rawNumericalDiagnostic(),
                input.triangleDebug());
    }

    private static Fixture input() {
        FrameCamera camera = new FrameCamera(
                new Matrix4f().perspective(
                        (float) Math.toRadians(70.0),
                        16.0F / 9.0F,
                        512.0F,
                        0.05F,
                        true),
                new Matrix4f().rotateY(0.25F),
                new Matrix4f().translation(0.25F, -0.5F, 0.75F),
                101.0,
                64.0,
                -33.0,
                101.25,
                63.5,
                -32.75);
        TerrainScene.ResidentSceneView scene =
                new TerrainScene.ResidentSceneView(
                        3L,
                        0x1020_3040_5060_7080L,
                        96,
                        48,
                        -48,
                        4L,
                        5L,
                        6L);
        IntegratorFrameInput input = new IntegratorFrameInput(
                camera,
                320,
                180,
                AstronomyState.atSolarHourAngle(
                        0.7F,
                        new AstronomySettings(-45, 270)),
                0x1234_5678,
                37,
                19,
                7,
                true,
                PostProcessingMode.NRD_FSR,
                TransparentGuideMode.REFLECTION_AND_TRANSMISSION,
                new LightingSettings.Snapshot(
                        4, -8, 12, 7L),
                new MaterialSettings.Snapshot(90, 8L),
                true,
                true,
                true);
        return new Fixture(input, scene);
    }

    private record Fixture(
            IntegratorFrameInput input,
            TerrainScene.ResidentSceneView scene) {
    }
}
