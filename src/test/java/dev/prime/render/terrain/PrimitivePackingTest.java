package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class PrimitivePackingTest {
    @Test
    void packsTwoHalfPrecisionCoordinatesInLittleWordOrder() {
        int packed = PrimitivePacking.packHalf2(0.25F, 0.75F);
        float x = Float.float16ToFloat((short) (packed & 0xffff));
        float y = Float.float16ToFloat((short) (packed >>> 16));
        assertEquals(0.25F, x);
        assertEquals(0.75F, y);
    }

    @Test
    void constantUvKeepsAdjacentTexelCentersDistinctOnA4096Atlas() {
        float first = 3073.5F / 4096.0F;
        float second = 3074.5F / 4096.0F;
        assertEquals(
                PrimitivePacking.packHalf2(first, first),
                PrimitivePacking.packHalf2(second, second));
        assertNotEquals(
                PrimitivePacking.packConstantUv(first),
                PrimitivePacking.packConstantUv(second));
        assertEquals(
                first,
                Float.intBitsToFloat(PrimitivePacking.packConstantUv(first)));
        assertEquals(
                PrimitivePacking.CONSTANT_UV_DENSITY,
                Float.floatToRawIntBits(-0.0F));
    }

    @Test
    void convertsArgbTintToRgba8() {
        assertEquals(0x80102040, PrimitivePacking.packTint(0x80402010));
        assertEquals(0xffffffff, PrimitivePacking.packTint(-1));
    }

    @Test
    void materialFlagsKeepCoverageAndAnimationIndependent() {
        assertEquals(0, PrimitivePacking.packFlags(false, false));
        assertEquals(PrimitivePacking.FLAG_CUTOUT, PrimitivePacking.packFlags(true, false));
        assertEquals(
                PrimitivePacking.FLAG_ANIMATED_TEXTURE,
                PrimitivePacking.packFlags(false, true));
        assertEquals(3, PrimitivePacking.packFlags(true, true));
        assertEquals(
                PrimitivePacking.FLAG_TRANSMISSIVE | PrimitivePacking.FLAG_THIN_WALLED,
                PrimitivePacking.packFlags(false, false, true, true, false, false));
        assertEquals(
                PrimitivePacking.FLAG_TRANSMISSIVE | PrimitivePacking.FLAG_WATER,
                PrimitivePacking.packFlags(false, false, true, false, true, false));
        assertEquals(
                PrimitivePacking.FLAG_CUTOUT
                        | PrimitivePacking.FLAG_THIN_WALLED
                        | PrimitivePacking.FLAG_FOLIAGE,
                PrimitivePacking.packFlags(true, false, false, true, false, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimitivePacking.packFlags(false, false, false, true, false, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimitivePacking.packFlags(false, false, true, true, true, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimitivePacking.packFlags(false, false, false, true, false, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimitivePacking.packFlags(true, false, true, true, false, true));
    }

    @Test
    void flagsAndEmitterIndexRoundTripAcrossTheWholeAbiRange() {
        int flags = PrimitivePacking.FLAG_CUTOUT
                | PrimitivePacking.FLAG_LABPBR_SPECULAR
                | PrimitivePacking.FLAG_TANGENT_NEGATIVE;
        int tint = PrimitivePacking.packTintFlags(
                PrimitivePacking.packTint(0x80402010), flags);
        assertEquals(0x00102040, tint & 0x00ff_ffff);
        for (int emitter : new int[] {
            PrimitivePacking.NO_EMITTER_INDEX, 0, 1, 1024, PrimitivePacking.MAX_EMITTER_INDEX
        }) {
            int packed = PrimitivePacking.packFlagsEmitter(flags, emitter);
            assertEquals(flags, PrimitivePacking.unpackFlags(tint, packed));
            assertEquals(emitter, PrimitivePacking.unpackEmitterIndex(packed));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimitivePacking.packFlagsEmitter(flags, PrimitivePacking.MAX_EMITTER_INDEX + 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> PrimitivePacking.packFlagsEmitter(1 << 9, 0));
    }

    @Test
    void octahedralEncodingPreservesNormalDirection() {
        assertNormalDirection(1.0F, 0.0F, 0.0F);
        assertNormalDirection(0.0F, -1.0F, 0.0F);
        assertNormalDirection(0.0F, 0.0F, -1.0F);
        assertNormalDirection(0.25F, -0.5F, 0.75F);
    }

    @Test
    void labPbrAvailabilityAndTangentHandednessUseIndependentBits() {
        int flags = PrimitivePacking.withLabPbr(
                PrimitivePacking.FLAG_CUTOUT, true, true, true);
        assertEquals(
                PrimitivePacking.FLAG_CUTOUT
                        | PrimitivePacking.FLAG_LABPBR_NORMAL
                        | PrimitivePacking.FLAG_LABPBR_SPECULAR
                        | PrimitivePacking.FLAG_TANGENT_NEGATIVE,
                flags);
        assertEquals(0, PrimitivePacking.withLabPbr(0, false, false, true));

        int normal = PrimitivePacking.packOctahedralNormal(0.0F, 0.0F, 1.0F);
        long positive = PrimitivePacking.packTriangleTangent(
                1.0F, 0.0F, 0.0F,
                0.0F, 1.0F, 0.0F,
                1.0F, 0.0F,
                0.0F, 1.0F,
                normal);
        assertPackedNormalDirection((int) positive, 1.0F, 0.0F, 0.0F);
        assertFalse((positive & 0x1_0000_0000L) != 0L);

        long negative = PrimitivePacking.packTriangleTangent(
                1.0F, 0.0F, 0.0F,
                0.0F, 1.0F, 0.0F,
                1.0F, 0.0F,
                0.0F, -1.0F,
                normal);
        assertTrue((negative & 0x1_0000_0000L) != 0L);
    }

    @Test
    void triangleNormalPreservesRotatedCutoutGeometryInsteadOfCardinalFallback() {
        int packed = PrimitivePacking.packTriangleNormal(
                1.0F, 0.0F, 1.0F,
                0.0F, 1.0F, 0.0F,
                0.0F, 0.0F, 1.0F);
        assertPackedNormalDirection(packed, -1.0F, 0.0F, 1.0F);

        int reversedWinding = PrimitivePacking.packTriangleNormal(
                0.0F, 1.0F, 0.0F,
                1.0F, 0.0F, 1.0F,
                0.0F, 0.0F, 1.0F);
        assertPackedNormalDirection(reversedWinding, -1.0F, 0.0F, 1.0F);

        int degenerate = PrimitivePacking.packTriangleNormal(
                1.0F, 0.0F, 0.0F,
                2.0F, 0.0F, 0.0F,
                0.0F, -1.0F, 0.0F);
        assertPackedNormalDirection(degenerate, 0.0F, -1.0F, 0.0F);
    }

    @Test
    void uvDensityUsesTheLargestWorldToUvSingularValue() {
        int packed = PrimitivePacking.packUvDensity(
                1.0F, 0.0F, 0.0F,
                0.0F, 1.0F, 0.0F,
                0.25F, 0.0F,
                0.0F, 0.5F);
        assertEquals(0.5F, Float.intBitsToFloat(packed), 1.0e-7F);
        assertEquals(0.0F, Float.intBitsToFloat(PrimitivePacking.packUvDensity(
                1.0F, 0.0F, 0.0F,
                2.0F, 0.0F, 0.0F,
                1.0F, 0.0F,
                2.0F, 0.0F)));
    }

    @Test
    void meshLayoutRejectsMismatchedArrayLengths() {
        CpuSectionMesh mesh = new CpuSectionMesh(
                new float[9], new int[CpuSectionMesh.PRIMITIVE_WORDS], 1, 0, 0,
                OpacityMicromapData.EMPTY, CpuSectionLights.EMPTY);
        assertEquals(68L, mesh.byteSize());
        assertThrows(IllegalArgumentException.class, () -> new CpuSectionMesh(
                new float[8], new int[CpuSectionMesh.PRIMITIVE_WORDS], 1, 0, 0,
                OpacityMicromapData.EMPTY, CpuSectionLights.EMPTY));
        assertThrows(IllegalArgumentException.class, () -> new CpuSectionMesh(
                new float[9], new int[CpuSectionMesh.PRIMITIVE_WORDS - 1], 1, 0, 0,
                OpacityMicromapData.EMPTY, CpuSectionLights.EMPTY));
    }

    private static void assertNormalDirection(float x, float y, float z) {
        float inverseLength = 1.0F / (float) Math.sqrt(x * x + y * y + z * z);
        x *= inverseLength;
        y *= inverseLength;
        z *= inverseLength;
        int packed = PrimitivePacking.packOctahedralNormal(x, y, z);
        assertPackedNormalDirection(packed, x, y, z);
    }

    private static void assertPackedNormalDirection(int packed, float x, float y, float z) {
        float inverseLength = 1.0F / (float) Math.sqrt(x * x + y * y + z * z);
        x *= inverseLength;
        y *= inverseLength;
        z *= inverseLength;
        float decodedX = Math.max(-1.0F, (short) packed / 32767.0F);
        float decodedY = Math.max(-1.0F, (short) (packed >>> 16) / 32767.0F);
        float decodedZ = 1.0F - Math.abs(decodedX) - Math.abs(decodedY);
        if (decodedZ < 0.0F) {
            float oldX = decodedX;
            decodedX = (1.0F - Math.abs(decodedY)) * Math.copySign(1.0F, oldX);
            decodedY = (1.0F - Math.abs(oldX)) * Math.copySign(1.0F, decodedY);
        }
        float decodedInverseLength = 1.0F / (float) Math.sqrt(
                decodedX * decodedX + decodedY * decodedY + decodedZ * decodedZ);
        float dot = x * decodedX * decodedInverseLength
                + y * decodedY * decodedInverseLength
                + z * decodedZ * decodedInverseLength;
        assertTrue(dot > 0.9999F, () -> "Decoded normal dot product was " + dot);
    }
}
