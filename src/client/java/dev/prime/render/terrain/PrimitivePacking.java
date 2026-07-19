package dev.prime.render.terrain;

public final class PrimitivePacking {
    public static final int FLAG_CUTOUT = 1;
    public static final int FLAG_ANIMATED_TEXTURE = 1 << 1;
    public static final int FLAG_TRANSMISSIVE = 1 << 2;
    public static final int FLAG_THIN_WALLED = 1 << 3;
    public static final int FLAG_WATER = 1 << 4;
    public static final int FLAG_FOLIAGE = 1 << 5;
    public static final int FLAG_LABPBR_NORMAL = 1 << 6;
    public static final int FLAG_LABPBR_SPECULAR = 1 << 7;
    public static final int FLAG_TANGENT_NEGATIVE = 1 << 8;
    public static final int FLAG_MASK = (1 << 9) - 1;
    public static final int NO_EMITTER_INDEX = -1;
    public static final int MAX_EMITTER_INDEX = (1 << 23) - 2;

    private PrimitivePacking() {
    }

    /**
     * Packs the nine material flags and the local light-emitter index without truncation.
     * Zero in the upper field means no emitter; every real index is stored plus one.
     */
    public static int packFlagsEmitter(int flags, int emitterIndex) {
        if ((flags & ~FLAG_MASK) != 0) {
            throw new IllegalArgumentException("Primitive flags exceed their nine-bit ABI field");
        }
        if (emitterIndex < NO_EMITTER_INDEX || emitterIndex > MAX_EMITTER_INDEX) {
            throw new IllegalArgumentException("Primitive emitter index exceeds its 23-bit ABI field");
        }
        int encodedEmitter = emitterIndex == NO_EMITTER_INDEX ? 0 : emitterIndex + 1;
        return flags | encodedEmitter << 9;
    }

    public static int unpackFlags(int packed) {
        return packed & FLAG_MASK;
    }

    public static int unpackEmitterIndex(int packed) {
        int encoded = packed >>> 9;
        return encoded == 0 ? NO_EMITTER_INDEX : encoded - 1;
    }

    public static int packHalf2(float x, float y) {
        int low = Float.floatToFloat16(x) & 0xffff;
        int high = Float.floatToFloat16(y) & 0xffff;
        return low | high << 16;
    }

    public static int packTint(int argb) {
        int alpha = argb >>> 24;
        int red = argb >>> 16 & 0xff;
        int green = argb >>> 8 & 0xff;
        int blue = argb & 0xff;
        return red | green << 8 | blue << 16 | alpha << 24;
    }

    public static int packFlags(boolean cutout, boolean animatedTexture) {
        return packFlags(cutout, animatedTexture, false, false, false, false);
    }

    public static int packFlags(
            boolean cutout,
            boolean animatedTexture,
            boolean transmissive,
            boolean thinWalled,
            boolean water,
            boolean foliage) {
        if (foliage && (!cutout || !thinWalled || transmissive || water)) {
            throw new IllegalArgumentException(
                    "Foliage must be a cutout, non-volume thin-walled primitive");
        }
        if (thinWalled && !transmissive && !foliage) {
            throw new IllegalArgumentException(
                    "Only transmissive or foliage primitives may be thin-walled");
        }
        if (water && (!transmissive || thinWalled)) {
            throw new IllegalArgumentException("Water must be a solid transmissive medium");
        }
        return (cutout ? FLAG_CUTOUT : 0)
                | (animatedTexture ? FLAG_ANIMATED_TEXTURE : 0)
                | (transmissive ? FLAG_TRANSMISSIVE : 0)
                | (thinWalled ? FLAG_THIN_WALLED : 0)
                | (water ? FLAG_WATER : 0)
                | (foliage ? FLAG_FOLIAGE : 0);
    }

    public static int withLabPbr(
            int flags,
            boolean normalMap,
            boolean specularMap,
            boolean tangentNegative) {
        return flags
                | (normalMap ? FLAG_LABPBR_NORMAL : 0)
                | (specularMap ? FLAG_LABPBR_SPECULAR : 0)
                | (normalMap && tangentNegative ? FLAG_TANGENT_NEGATIVE : 0);
    }

    /**
     * Packs the UV tangent into the low 32 bits and reports negative bitangent handedness in bit
     * 32 of the returned value.
     * The geometric normal remains a separate field so normal mapping cannot perturb traversal
     * offsets, medium entry/exit tests, or ray-cone incidence.
     */
    public static long packTriangleTangent(
            float edgeOneX,
            float edgeOneY,
            float edgeOneZ,
            float edgeTwoX,
            float edgeTwoY,
            float edgeTwoZ,
            float deltaU1,
            float deltaV1,
            float deltaU2,
            float deltaV2,
            int packedNormal) {
        float determinant = deltaU1 * deltaV2 - deltaU2 * deltaV1;
        float normalX = unpackOctahedralComponent(packedNormal, true);
        float normalY = unpackOctahedralComponent(packedNormal, false);
        float normalZ = 1.0F - Math.abs(normalX) - Math.abs(normalY);
        if (normalZ < 0.0F) {
            float oldX = normalX;
            normalX = (1.0F - Math.abs(normalY)) * Math.copySign(1.0F, oldX);
            normalY = (1.0F - Math.abs(oldX)) * Math.copySign(1.0F, normalY);
        }
        float inverseNormalLength = 1.0F / (float) Math.sqrt(Math.max(
                normalX * normalX + normalY * normalY + normalZ * normalZ, 1.0e-20F));
        normalX *= inverseNormalLength;
        normalY *= inverseNormalLength;
        normalZ *= inverseNormalLength;
        float tangentX;
        float tangentY;
        float tangentZ;
        float bitangentX;
        float bitangentY;
        float bitangentZ;
        if (Math.abs(determinant) > 1.0e-20F && Float.isFinite(determinant)) {
            float inverse = 1.0F / determinant;
            tangentX = (edgeOneX * deltaV2 - edgeTwoX * deltaV1) * inverse;
            tangentY = (edgeOneY * deltaV2 - edgeTwoY * deltaV1) * inverse;
            tangentZ = (edgeOneZ * deltaV2 - edgeTwoZ * deltaV1) * inverse;
            bitangentX = (edgeTwoX * deltaU1 - edgeOneX * deltaU2) * inverse;
            bitangentY = (edgeTwoY * deltaU1 - edgeOneY * deltaU2) * inverse;
            bitangentZ = (edgeTwoZ * deltaU1 - edgeOneZ * deltaU2) * inverse;
        } else {
            float axisX = Math.abs(normalX) < 0.9F ? 1.0F : 0.0F;
            float axisY = axisX == 0.0F ? 1.0F : 0.0F;
            tangentX = axisY * normalZ;
            tangentY = -axisX * normalZ;
            tangentZ = axisX * normalY - axisY * normalX;
            bitangentX = normalY * tangentZ - normalZ * tangentY;
            bitangentY = normalZ * tangentX - normalX * tangentZ;
            bitangentZ = normalX * tangentY - normalY * tangentX;
        }
        float normalProjection = tangentX * normalX + tangentY * normalY + tangentZ * normalZ;
        tangentX -= normalProjection * normalX;
        tangentY -= normalProjection * normalY;
        tangentZ -= normalProjection * normalZ;
        float lengthSquared = tangentX * tangentX + tangentY * tangentY + tangentZ * tangentZ;
        if (!(lengthSquared > 1.0e-20F) || !Float.isFinite(lengthSquared)) {
            tangentX = Math.abs(normalX) < 0.9F ? 1.0F : 0.0F;
            tangentY = tangentX == 0.0F ? 1.0F : 0.0F;
            tangentZ = 0.0F;
            normalProjection = tangentX * normalX + tangentY * normalY;
            tangentX -= normalProjection * normalX;
            tangentY -= normalProjection * normalY;
            tangentZ -= normalProjection * normalZ;
            lengthSquared = tangentX * tangentX + tangentY * tangentY + tangentZ * tangentZ;
        }
        float inverseLength = 1.0F / (float) Math.sqrt(Math.max(lengthSquared, 1.0e-20F));
        tangentX *= inverseLength;
        tangentY *= inverseLength;
        tangentZ *= inverseLength;
        float crossX = normalY * tangentZ - normalZ * tangentY;
        float crossY = normalZ * tangentX - normalX * tangentZ;
        float crossZ = normalX * tangentY - normalY * tangentX;
        boolean negative = crossX * bitangentX + crossY * bitangentY + crossZ * bitangentZ < 0.0F;
        return Integer.toUnsignedLong(packOctahedralNormal(tangentX, tangentY, tangentZ))
                | (negative ? 0x1_0000_0000L : 0L);
    }

    private static float unpackOctahedralComponent(int packed, boolean low) {
        short value = (short) (low ? packed : packed >>> 16);
        return Math.max(-1.0F, value / 32767.0F);
    }

    public static int packOctahedralNormal(float x, float y, float z) {
        float inverseLength = 1.0F / Math.max(1.0e-20F, Math.abs(x) + Math.abs(y) + Math.abs(z));
        x *= inverseLength;
        y *= inverseLength;
        z *= inverseLength;
        if (z < 0.0F) {
            float oldX = x;
            x = (1.0F - Math.abs(y)) * Math.copySign(1.0F, oldX);
            y = (1.0F - Math.abs(oldX)) * Math.copySign(1.0F, y);
        }
        int packedX = packSnorm16(x);
        int packedY = packSnorm16(y);
        return packedX & 0xffff | packedY << 16;
    }

    /**
     * Packs the true triangle normal, falling back to the baked cardinal direction only for a
     * degenerate primitive.
     *
     * <p>Minecraft's {@code BakedQuad.direction()} is restricted to the six block directions.
     * Treating it as a geometric normal snaps rotated models such as crossed grass and flowers to
     * an axis. Besides incorrect shading, that makes ray-cone incidence select excessively coarse
     * alpha mips and can turn covered cutout texels into light leaks.
     */
    public static int packTriangleNormal(
            float edgeOneX,
            float edgeOneY,
            float edgeOneZ,
            float edgeTwoX,
            float edgeTwoY,
            float edgeTwoZ,
            float fallbackX,
            float fallbackY,
            float fallbackZ) {
        float normalX = edgeOneY * edgeTwoZ - edgeOneZ * edgeTwoY;
        float normalY = edgeOneZ * edgeTwoX - edgeOneX * edgeTwoZ;
        float normalZ = edgeOneX * edgeTwoY - edgeOneY * edgeTwoX;
        float lengthSquared = normalX * normalX + normalY * normalY + normalZ * normalZ;
        if (!(lengthSquared > 1.0e-20F) || !Float.isFinite(lengthSquared)) {
            normalX = fallbackX;
            normalY = fallbackY;
            normalZ = fallbackZ;
            lengthSquared = normalX * normalX + normalY * normalY + normalZ * normalZ;
        } else if (normalX * fallbackX + normalY * fallbackY + normalZ * fallbackZ < 0.0F) {
            // Vertex winding is normally authoritative, but resource-provided baked quads may
            // disagree. Preserve the model's outward hemisphere without snapping its direction.
            normalX = -normalX;
            normalY = -normalY;
            normalZ = -normalZ;
        }
        float inverseLength = 1.0F / (float) Math.sqrt(Math.max(lengthSquared, 1.0e-20F));
        return packOctahedralNormal(
                normalX * inverseLength,
                normalY * inverseLength,
                normalZ * inverseLength);
    }

    /**
     * Packs the largest normalized-atlas UV change per world-space unit as one float.
     *
     * <p>This is the largest singular value of the triangle's world-to-UV differential. The hit
     * shader combines it with the actual atlas extent and the ray-cone footprint, so arbitrary
     * baked-model scaling is handled without storing triangle positions in the shader record.
     */
    public static int packUvDensity(
            float edge1X,
            float edge1Y,
            float edge1Z,
            float edge2X,
            float edge2Y,
            float edge2Z,
            float deltaU1,
            float deltaV1,
            float deltaU2,
            float deltaV2) {
        float normalX = edge1Y * edge2Z - edge1Z * edge2Y;
        float normalY = edge1Z * edge2X - edge1X * edge2Z;
        float normalZ = edge1X * edge2Y - edge1Y * edge2X;
        float denominator = normalX * normalX + normalY * normalY + normalZ * normalZ;
        if (!(denominator > 1.0e-20F) || !Float.isFinite(denominator)) {
            return Float.floatToRawIntBits(0.0F);
        }

        // cross(edge2, normal) and cross(normal, edge1) are the reciprocal tangent basis.
        float firstBasisX = edge2Y * normalZ - edge2Z * normalY;
        float firstBasisY = edge2Z * normalX - edge2X * normalZ;
        float firstBasisZ = edge2X * normalY - edge2Y * normalX;
        float secondBasisX = normalY * edge1Z - normalZ * edge1Y;
        float secondBasisY = normalZ * edge1X - normalX * edge1Z;
        float secondBasisZ = normalX * edge1Y - normalY * edge1X;
        float inverseDenominator = 1.0F / denominator;
        float gradientUx = (deltaU1 * firstBasisX + deltaU2 * secondBasisX) * inverseDenominator;
        float gradientUy = (deltaU1 * firstBasisY + deltaU2 * secondBasisY) * inverseDenominator;
        float gradientUz = (deltaU1 * firstBasisZ + deltaU2 * secondBasisZ) * inverseDenominator;
        float gradientVx = (deltaV1 * firstBasisX + deltaV2 * secondBasisX) * inverseDenominator;
        float gradientVy = (deltaV1 * firstBasisY + deltaV2 * secondBasisY) * inverseDenominator;
        float gradientVz = (deltaV1 * firstBasisZ + deltaV2 * secondBasisZ) * inverseDenominator;

        float uu = gradientUx * gradientUx + gradientUy * gradientUy + gradientUz * gradientUz;
        float vv = gradientVx * gradientVx + gradientVy * gradientVy + gradientVz * gradientVz;
        float uv = gradientUx * gradientVx + gradientUy * gradientVy + gradientUz * gradientVz;
        float discriminant = (uu - vv) * (uu - vv) + 4.0F * uv * uv;
        float largestEigenvalue = 0.5F * (uu + vv + (float) Math.sqrt(Math.max(discriminant, 0.0F)));
        float density = (float) Math.sqrt(Math.max(largestEigenvalue, 0.0F));
        return Float.floatToRawIntBits(Float.isFinite(density) ? density : 0.0F);
    }

    private static int packSnorm16(float value) {
        float clamped = Math.max(-1.0F, Math.min(1.0F, value));
        return Math.round(clamped * 32767.0F) & 0xffff;
    }
}
