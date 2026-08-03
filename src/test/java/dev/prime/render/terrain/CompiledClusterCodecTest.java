package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CompiledClusterCodecTest {
    @Test
    void roundTripPreservesSharedMacroPrimitiveLayout() {
        int[] primitive = {
            PrimitivePacking.packHalf2(0.0F, 0.0F),
            PrimitivePacking.packHalf2(1.0F, 0.0F),
            PrimitivePacking.packHalf2(0.0F, 1.0F),
            PrimitivePacking.packTintFlags(PrimitivePacking.packTint(-1), 0),
            PrimitivePacking.packOctahedralNormal(0.0F, 0.0F, 1.0F),
            PrimitivePacking.packFlagsEmitter(0, PrimitivePacking.NO_EMITTER_INDEX),
            Float.floatToRawIntBits(-1.0F),
            PrimitivePacking.packOctahedralNormal(1.0F, 0.0F, 0.0F)
        };
        CpuSectionMesh section = new CpuSectionMesh(
                new float[] {
                    0, 0, 0, 1, 0, 0, 1, 1, 0,
                    0, 0, 0, 1, 1, 0, 0, 1, 0
                },
                primitive,
                2,
                0,
                0,
                2,
                0,
                0,
                OpacityMicromapData.EMPTY,
                CpuSectionLights.EMPTY);
        CompiledCluster source = new CompiledCluster(
                0L, 0, 0, 0, CpuClusterMesh.fromSegments(List.of(section)));

        byte[] encoded = CompiledClusterCodec.encode(source);
        CpuClusterMesh decoded = CompiledClusterCodec.decode(encoded).mesh();

        assertArrayEquals(encoded, CompiledClusterCodec.encode(
                new CompiledCluster(0L, 0, 0, 0, decoded)));
        assertEquals(2L, decoded.opaqueMacroTriangleCount());
        assertEquals(1L, decoded.primitiveCount());
        assertEquals(CpuSectionMesh.PRIMITIVE_WORDS,
                decoded.segments().getFirst().primitiveRecords().length);
    }

    @Test
    void canonicalRoundTripPreservesTheCompleteUploadInput() {
        int flags = PrimitivePacking.packFlags(true, false);
        CpuSectionMesh section = new CpuSectionMesh(
                new float[] {
                    -0.0F, 0.0F, 0.0F,
                    1.0F, 0.0F, 0.0F,
                    0.0F, 1.0F, 0.0F
                },
                new int[] {
                    PrimitivePacking.packHalf2(0.0F, 0.0F),
                    PrimitivePacking.packHalf2(1.0F, 0.0F),
                    PrimitivePacking.packHalf2(0.0F, 1.0F),
                    PrimitivePacking.packTintFlags(
                            PrimitivePacking.packTint(-1), flags),
                    PrimitivePacking.packOctahedralNormal(0.0F, 0.0F, 1.0F),
                    PrimitivePacking.packFlagsEmitter(
                            flags, PrimitivePacking.NO_EMITTER_INDEX),
                    Float.floatToRawIntBits(1.0F),
                    PrimitivePacking.packOctahedralNormal(1.0F, 0.0F, 0.0F)
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
        assertEquals(1, decoded.mesh().opacityMicromap().triangleCount());
    }

    @Test
    void versionThreePrimitivePackingMigratesWithoutInventingFrontFaceSelection() {
        int flags = PrimitivePacking.FLAG_CUTOUT
                | PrimitivePacking.FLAG_LABPBR_SPECULAR;
        int[] primitive = {
            PrimitivePacking.packHalf2(0.0F, 0.0F),
            PrimitivePacking.packHalf2(1.0F, 0.0F),
            PrimitivePacking.packHalf2(0.0F, 1.0F),
            PrimitivePacking.packTintFlags(
                    PrimitivePacking.packTint(-1), flags),
            PrimitivePacking.packOctahedralNormal(0.0F, 0.0F, 1.0F),
            PrimitivePacking.packDynamicFlags(flags, 17, true),
            Float.floatToRawIntBits(1.0F),
            PrimitivePacking.packOctahedralNormal(1.0F, 0.0F, 0.0F)
        };
        CpuSectionMesh section = new CpuSectionMesh(
                new float[] {
                    0.0F, 0.0F, 0.0F,
                    1.0F, 0.0F, 0.0F,
                    0.0F, 1.0F, 0.0F
                },
                primitive,
                0,
                1,
                0,
                OpacityMicromapData.fullyUnknown(1),
                CpuSectionLights.EMPTY);
        byte[] encoded = CompiledClusterCodec.encode(new CompiledCluster(
                0L,
                0,
                0,
                0,
                CpuClusterMesh.fromSegments(List.of(section))));
        PayloadOffsets offsets = firstPayloadOffsets(encoded);
        byte[] legacyBytes = withoutCurrentSegmentMacroCounts(encoded);
        ByteBuffer legacy = littleEndian(legacyBytes);
        legacy.putInt(4, 3);
        legacy.putInt(
                offsets.primitives() - 3 * Integer.BYTES + 5 * Integer.BYTES,
                flags >>> 8
                        | 17 << 1
                        | PrimitivePacking.VISIBLE_EMISSION_FLAG
                        | PrimitivePacking.DYNAMIC_TEXTURE_FLAG);

        CompiledCluster decoded = CompiledClusterCodec.decode(legacyBytes);
        int[] decodedPrimitive =
                decoded.mesh().segments().getFirst().primitiveRecords();
        int decodedFlags = PrimitivePacking.unpackFlags(
                decodedPrimitive[3], decodedPrimitive[5]);

        assertEquals(flags, decodedFlags);
        assertEquals(
                0, decodedFlags & PrimitivePacking.FLAG_FRONT_FACE_ONLY);
        assertEquals(
                17,
                PrimitivePacking.unpackDynamicTextureIndex(
                        decodedPrimitive[5]));
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

    @Test
    void decodedUploadInputRejectsNonFiniteAndOutOfContractPrimitiveData() {
        CpuSectionMesh section = new CpuSectionMesh(
                new float[] {
                    0.0F, 0.0F, 0.0F,
                    1.0F, 0.0F, 0.0F,
                    0.0F, 1.0F, 0.0F
                },
                new int[] {
                    PrimitivePacking.packHalf2(0.0F, 0.0F),
                    PrimitivePacking.packHalf2(1.0F, 0.0F),
                    PrimitivePacking.packHalf2(0.0F, 1.0F),
                    PrimitivePacking.packTintFlags(
                            PrimitivePacking.packTint(-1), 0),
                    PrimitivePacking.packOctahedralNormal(0.0F, 0.0F, 1.0F),
                    PrimitivePacking.packFlagsEmitter(
                            0, PrimitivePacking.NO_EMITTER_INDEX),
                    Float.floatToRawIntBits(1.0F),
                    PrimitivePacking.packOctahedralNormal(1.0F, 0.0F, 0.0F)
                },
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
        PayloadOffsets offsets = firstPayloadOffsets(valid);

        byte[] nonFinitePosition = valid.clone();
        littleEndian(nonFinitePosition).putInt(
                offsets.positions(), Float.floatToRawIntBits(Float.NaN));
        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterCodec.decode(nonFinitePosition));

        byte[] nonFiniteUv = valid.clone();
        littleEndian(nonFiniteUv).putInt(offsets.primitives(), 0x0000_7c00);
        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterCodec.decode(nonFiniteUv));

        byte[] invalidEmitter = valid.clone();
        littleEndian(invalidEmitter).putInt(
                offsets.primitives() + 5 * Integer.BYTES,
                PrimitivePacking.packFlagsEmitter(0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterCodec.decode(invalidEmitter));

        byte[] wrongCategory = valid.clone();
        int transmissive = PrimitivePacking.packFlags(
                false, false, true, false, false, false);
        littleEndian(wrongCategory).putInt(
                offsets.primitives() + 3 * Integer.BYTES,
                PrimitivePacking.packTintFlags(
                        PrimitivePacking.packTint(-1), transmissive));
        littleEndian(wrongCategory).putInt(
                offsets.primitives() + 5 * Integer.BYTES,
                PrimitivePacking.packFlagsEmitter(
                        transmissive, PrimitivePacking.NO_EMITTER_INDEX));
        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterCodec.decode(wrongCategory));

        byte[] nonFiniteUvDensity = valid.clone();
        littleEndian(nonFiniteUvDensity).putInt(
                offsets.primitives() + 6 * Integer.BYTES,
                Float.floatToRawIntBits(Float.POSITIVE_INFINITY));
        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterCodec.decode(nonFiniteUvDensity));
    }

    private static PayloadOffsets firstPayloadOffsets(byte[] encoded) {
        ByteBuffer input = littleEndian(encoded);
        input.position(56);
        input.position(input.position() + 6 * Integer.BYTES);
        int positionCount = input.getInt();
        int positions = input.position();
        input.position(Math.addExact(
                positions, Math.multiplyExact(positionCount, Float.BYTES)));
        int primitiveCount = input.getInt();
        if (positionCount != 9 || primitiveCount != CpuSectionMesh.PRIMITIVE_WORDS) {
            throw new AssertionError("Unexpected single-triangle fixture layout");
        }
        return new PayloadOffsets(positions, input.position());
    }

    private static byte[] withoutCurrentSegmentMacroCounts(byte[] encoded) {
        int macroCounts = 3 * Integer.BYTES;
        int macroOffset = 56 + 3 * Integer.BYTES;
        byte[] result = new byte[encoded.length - macroCounts];
        System.arraycopy(encoded, 0, result, 0, macroOffset);
        System.arraycopy(
                encoded,
                macroOffset + macroCounts,
                result,
                macroOffset,
                encoded.length - macroOffset - macroCounts);
        return result;
    }

    private static ByteBuffer littleEndian(byte[] data) {
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    }

    private record PayloadOffsets(int positions, int primitives) {
    }
}
