package dev.prime.render.terrain;

public final class PrimitivePacking {
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

    private static int packSnorm16(float value) {
        float clamped = Math.max(-1.0F, Math.min(1.0F, value));
        return Math.round(clamped * 32767.0F) & 0xffff;
    }
}
