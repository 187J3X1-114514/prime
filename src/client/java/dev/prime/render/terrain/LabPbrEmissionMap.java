package dev.prime.render.terrain;

import java.util.Arrays;

/** Immutable CPU view of the LabPBR specular alpha channel used by light extraction. */
public final class LabPbrEmissionMap {
    private final byte[] encoded;
    private final int frameWidth;
    private final int frameHeight;
    private final int columns;
    private final int frameCount;

    private LabPbrEmissionMap(
            byte[] encoded,
            int frameWidth,
            int frameHeight,
            int columns,
            int frameCount) {
        this.encoded = encoded;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.columns = columns;
        this.frameCount = frameCount;
    }

    /** Returns null only when every texel uses LabPBR's 255 "not authored" sentinel. */
    public static LabPbrEmissionMap fromSpecular(
            int[] argb,
            int width,
            int height,
            int frameWidth,
            int frameHeight,
            int columns,
            int frameCount) {
        if (argb.length != Math.multiplyExact(width, height)) {
            throw new IllegalArgumentException("Specular pixel count does not match its dimensions");
        }
        byte[] encoded = new byte[argb.length];
        boolean authored = false;
        for (int index = 0; index < argb.length; index++) {
            int alpha = argb[index] >>> 24;
            encoded[index] = (byte) alpha;
            authored |= alpha < 255;
        }
        return authored
                ? new LabPbrEmissionMap(
                        encoded, frameWidth, frameHeight, columns, frameCount)
                : null;
    }

    /** Samples the same clamped source frame and normalized sprite coordinates as the GPU atlas. */
    float sample(int requestedFrame, float localU, float localV) {
        int frame = this.frameCount == 1
                ? 0
                : Math.max(0, Math.min(requestedFrame, this.frameCount - 1));
        int x = Math.min((int) (clampUnit(localU) * this.frameWidth), this.frameWidth - 1);
        int y = Math.min((int) (clampUnit(localV) * this.frameHeight), this.frameHeight - 1);
        int frameX = frame % this.columns * this.frameWidth;
        int frameY = frame / this.columns * this.frameHeight;
        return decode(Byte.toUnsignedInt(
                this.encoded[(frameY + y) * (this.columns * this.frameWidth) + frameX + x]));
    }

    static float decode(int encoded) {
        if (encoded < 0 || encoded > 255) {
            throw new IllegalArgumentException("LabPBR emission must be an unsigned byte");
        }
        return encoded < 255 ? encoded / 254.0F : 0.0F;
    }

    private static float clampUnit(float value) {
        return Math.max(0.0F, Math.min(Math.nextDown(1.0F), value));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LabPbrEmissionMap map)) {
            return false;
        }
        return this.frameWidth == map.frameWidth
                && this.frameHeight == map.frameHeight
                && this.columns == map.columns
                && this.frameCount == map.frameCount
                && Arrays.equals(this.encoded, map.encoded);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(this.encoded);
        result = 31 * result + this.frameWidth;
        result = 31 * result + this.frameHeight;
        result = 31 * result + this.columns;
        return 31 * result + this.frameCount;
    }
}
