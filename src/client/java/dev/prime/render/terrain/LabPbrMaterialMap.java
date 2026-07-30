package dev.prime.render.terrain;

import java.util.Arrays;

/** Immutable CPU material texels used when voxel primitives bake LabPBR inputs. */
public record LabPbrMaterialMap(Pixels normal, Pixels specular) {
    static final int DEFAULT_NORMAL = 0xffff_8080;
    static final int DEFAULT_SPECULAR = 0xff00_0400;

    int sampleNormal(int requestedFrame, float localU, float localV) {
        return this.normal == null
                ? DEFAULT_NORMAL
                : packArgb(this.normal.sample(requestedFrame, localU, localV));
    }

    int sampleSpecular(int requestedFrame, float localU, float localV) {
        return this.specular == null
                ? DEFAULT_SPECULAR
                : packArgb(this.specular.sample(requestedFrame, localU, localV));
    }

    static int packArgb(int argb) {
        return argb >>> 16 & 0xff
                | (argb >>> 8 & 0xff) << 8
                | (argb & 0xff) << 16
                | (argb >>> 24) << 24;
    }

    public static final class Pixels {
        private final int[] argb;
        private final int imageWidth;
        private final int frameWidth;
        private final int frameHeight;
        private final int columns;
        private final int frameCount;

        public Pixels(
                int[] argb,
                int imageWidth,
                int frameWidth,
                int frameHeight,
                int columns,
                int frameCount) {
            if (imageWidth <= 0
                    || frameWidth <= 0
                    || frameHeight <= 0
                    || columns <= 0
                    || frameCount <= 0
                    || argb.length % imageWidth != 0
                    || (long) columns * frameWidth > imageWidth
                    || ((long) frameCount + columns - 1L) / columns * frameHeight
                            > argb.length / imageWidth) {
                throw new IllegalArgumentException(
                        "LabPBR material layout does not match its pixels");
            }
            this.argb = Arrays.copyOf(argb, argb.length);
            this.imageWidth = imageWidth;
            this.frameWidth = frameWidth;
            this.frameHeight = frameHeight;
            this.columns = columns;
            this.frameCount = frameCount;
        }

        int sample(int requestedFrame, float localU, float localV) {
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
            return this.argb[(frameY + y) * this.imageWidth + frameX + x];
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof Pixels pixels
                            && this.imageWidth == pixels.imageWidth
                            && this.frameWidth == pixels.frameWidth
                            && this.frameHeight == pixels.frameHeight
                            && this.columns == pixels.columns
                            && this.frameCount == pixels.frameCount
                            && Arrays.equals(this.argb, pixels.argb);
        }

        @Override
        public int hashCode() {
            int result = Arrays.hashCode(this.argb);
            result = 31 * result + this.imageWidth;
            result = 31 * result + this.frameWidth;
            result = 31 * result + this.frameHeight;
            result = 31 * result + this.columns;
            return 31 * result + this.frameCount;
        }
    }

    private static float clampUnit(float value) {
        return Math.max(0.0F, Math.min(Math.nextDown(1.0F), value));
    }
}
