package dev.prime.render.terrain;

import java.util.Arrays;

/** Immutable CPU view of the LabPBR normal-map alpha height channel. */
public final class LabPbrHeightMap {
    private final byte[] encoded;
    private final int imageWidth;
    private final int frameWidth;
    private final int frameHeight;
    private final int columns;
    private final int frameCount;
    private final byte[] frameMinimum;

    private LabPbrHeightMap(
            byte[] encoded,
            int imageWidth,
            int frameWidth,
            int frameHeight,
            int columns,
            int frameCount,
            byte[] frameMinimum) {
        this.encoded = encoded;
        this.imageWidth = imageWidth;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.columns = columns;
        this.frameCount = frameCount;
        this.frameMinimum = frameMinimum;
    }

    public static LabPbrHeightMap fromNormal(
            int[] argb,
            int width,
            int height,
            int frameWidth,
            int frameHeight,
            int columns,
            int frameCount) {
        if (width <= 0
                || height <= 0
                || frameWidth <= 0
                || frameHeight <= 0
                || columns <= 0
                || frameCount <= 0
                || argb.length != Math.multiplyExact(width, height)
                || (long) columns * frameWidth > width
                || ((long) frameCount + columns - 1L) / columns * frameHeight
                        > height) {
            throw new IllegalArgumentException(
                    "LabPBR height-map layout does not match its pixels");
        }
        byte[] encoded = new byte[argb.length];
        for (int index = 0; index < argb.length; index++) {
            encoded[index] = (byte) (argb[index] >>> 24);
        }
        byte[] frameMinimum = new byte[frameCount];
        Arrays.fill(frameMinimum, (byte) 0xff);
        for (int frame = 0; frame < frameCount; frame++) {
            int frameX = frame % columns * frameWidth;
            int frameY = frame / columns * frameHeight;
            int minimum = 255;
            for (int y = 0; y < frameHeight; y++) {
                for (int x = 0; x < frameWidth; x++) {
                    minimum = Math.min(
                            minimum,
                            Byte.toUnsignedInt(
                                    encoded[(frameY + y) * width + frameX + x]));
                }
            }
            frameMinimum[frame] = (byte) minimum;
        }
        return new LabPbrHeightMap(
                encoded,
                width,
                frameWidth,
                frameHeight,
                columns,
                frameCount,
                frameMinimum);
    }

    /**
     * Samples outward-only relief after rebasing the frame's lowest authored height to zero.
     *
     * <p>LabPBR height commonly occupies a narrow high-valued band because its original use is
     * inward parallax depth. Removing that per-frame DC offset prevents a flat map from lifting
     * the whole face and exposing every instance's perimeter skirt.
     */
    float sample(int requestedFrame, float localU, float localV) {
        int frame = this.frameCount == 1
                ? 0
                : Math.max(0, Math.min(requestedFrame, this.frameCount - 1));
        int x = Math.min(
                (int) (clampUnit(localU) * this.frameWidth),
                this.frameWidth - 1);
        int y = Math.min(
                (int) (clampUnit(localV) * this.frameHeight),
                this.frameHeight - 1);
        int frameX = frame % this.columns * this.frameWidth;
        int frameY = frame / this.columns * this.frameHeight;
        int encodedHeight = Byte.toUnsignedInt(
                this.encoded[(frameY + y) * this.imageWidth + frameX + x]);
        int minimum = Byte.toUnsignedInt(this.frameMinimum[frame]);
        return (encodedHeight - minimum) / 255.0F;
    }

    private static float clampUnit(float value) {
        return Math.max(0.0F, Math.min(Math.nextDown(1.0F), value));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LabPbrHeightMap map)) {
            return false;
        }
        return this.imageWidth == map.imageWidth
                && this.frameWidth == map.frameWidth
                && this.frameHeight == map.frameHeight
                && this.columns == map.columns
                && this.frameCount == map.frameCount
                && Arrays.equals(this.encoded, map.encoded);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(this.encoded);
        result = 31 * result + this.imageWidth;
        result = 31 * result + this.frameWidth;
        result = 31 * result + this.frameHeight;
        result = 31 * result + this.columns;
        return 31 * result + this.frameCount;
    }
}
