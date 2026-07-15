package dev.prime.render.terrain;

public final class PrimitivePacking {
    public static final int FLAG_CUTOUT = 1;
    public static final int FLAG_ANIMATED_TEXTURE = 1 << 1;
    public static final int FLAG_TRANSMISSIVE = 1 << 2;
    public static final int FLAG_THIN_WALLED = 1 << 3;
    public static final int FLAG_WATER = 1 << 4;
    public static final int FLAG_FOLIAGE = 1 << 5;

    private PrimitivePacking() {
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
