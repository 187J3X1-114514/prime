package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CompiledClusterCodecTest {
    @Test
    void canonicalRoundTripPreservesTheCompleteUploadInput() {
        CpuSectionMesh section = new CpuSectionMesh(
                new float[] {
                    -0.0F, 0.0F, 0.0F,
                    1.0F, 0.0F, 0.0F,
                    0.0F, 1.0F, 0.0F
                },
                new int[] {
                    0x0123_4567,
                    0x89ab_cdef,
                    3,
                    4,
                    5,
                    6,
                    7,
                    8
                },
                0,
                1,
                0,
                OpacityMicromapData.fullyUnknown(1),
                CpuSectionLights.EMPTY);
        CompiledCluster source = new CompiledCluster(
                0L,
                0,
                0,
                0,
                CpuClusterMesh.fromSegments(List.of(section)));

        byte[] encoded = CompiledClusterCodec.encode(source);
        CompiledCluster decoded = CompiledClusterCodec.decode(encoded);

        assertArrayEquals(encoded, CompiledClusterCodec.encode(decoded));
        assertEquals(
                CompiledClusterFingerprint.sha256Hex(source),
                CompiledClusterFingerprint.sha256Hex(decoded));
        assertEquals(1L, decoded.mesh().cutoutTriangleCount());
        assertEquals(
                1,
                decoded.mesh().opacityMicromap().triangleIndices().length);
    }

    @Test
    void malformedOrTruncatedPayloadsFailBeforePublication() {
        CpuSectionMesh section = new CpuSectionMesh(
                new float[9],
                new int[CpuSectionMesh.PRIMITIVE_WORDS],
                1,
                0,
                0,
                OpacityMicromapData.EMPTY,
                CpuSectionLights.EMPTY);
        byte[] valid = CompiledClusterCodec.encode(new CompiledCluster(
                0L,
                0,
                0,
                0,
                CpuClusterMesh.fromSegments(List.of(section))));
        byte[] wrongMagic = valid.clone();
        wrongMagic[0] ^= 1;

        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterCodec.decode(wrongMagic));
        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterCodec.decode(
                        Arrays.copyOf(valid, valid.length - 1)));
    }
}
