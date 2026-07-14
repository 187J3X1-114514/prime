package dev.prime.render.terrain;

import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.ints.IntList;
import dev.prime.mixin.SpriteContentsAccessor;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** A resolution-independent, surface-local importance distribution for an emissive triangle. */
final class EmissionDistribution {
    static final int SUBDIVISION = 16;
    static final int CELL_COUNT = SUBDIVISION * SUBDIVISION;
    private static final float MINIMUM_CELL_LUMINANCE = 1.0E-5F;
    private static final int STRATIFIED_SAMPLE_COUNT = 4;

    private final float[] aliasProbabilities;
    private final int[] aliases;
    private final float[] probabilityMasses;
    private final float meanLuminance;

    private EmissionDistribution(float[] weights) {
        this.aliasProbabilities = new float[CELL_COUNT];
        this.aliases = new int[CELL_COUNT];
        this.probabilityMasses = new float[CELL_COUNT];
        double sum = 0.0;
        for (float weight : weights) {
            sum += weight;
        }
        if (!(sum > 0.0) || !Double.isFinite(sum)) {
            throw new IllegalArgumentException("Emission importance weights must have positive finite mass");
        }
        this.meanLuminance = (float) (sum / CELL_COUNT);
        buildAliasTable(weights, sum, this.aliasProbabilities, this.aliases, this.probabilityMasses);
    }

    static EmissionDistribution build(Key key) {
        float[] weights = new float[CELL_COUNT];
        try {
            fillTextureWeights(key, weights);
        } catch (RuntimeException exception) {
            // A resource reload can retire SpriteContents while an already-cancelled mesh job is
            // winding down. Uniform support is still unbiased because radiance is read from the
            // live atlas; it only loses the variance reduction of the static importance table.
            java.util.Arrays.fill(weights, 1.0F);
        }
        for (int index = 0; index < weights.length; index++) {
            weights[index] = Math.max(weights[index], MINIMUM_CELL_LUMINANCE);
        }
        return new EmissionDistribution(weights);
    }

    static EmissionDistribution uniform() {
        float[] weights = new float[CELL_COUNT];
        java.util.Arrays.fill(weights, 1.0F);
        return new EmissionDistribution(weights);
    }

    private static void fillTextureWeights(Key key, float[] weights) {
        TextureAtlasSprite sprite = key.sprite;
        SpriteContents contents = sprite.contents();
        NativeImage image = ((SpriteContentsAccessor) (Object) contents).prime$originalImage();
        int[] frames = frames(contents);
        int columns = Math.max(image.getWidth() / contents.width(), 1);
        float[] tint = linearTint(key.tintArgb);
        for (int cellIndex = 0; cellIndex < CELL_COUNT; cellIndex++) {
            Cell cell = cell(cellIndex);
            float total = 0.0F;
            for (int sample = 0; sample < STRATIFIED_SAMPLE_COUNT; sample++) {
                float[] barycentric = cell.samplePoint(sample);
                float atlasU = interpolate(key.u0(), key.u1(), key.u2(), barycentric);
                float atlasV = interpolate(key.v0(), key.v1(), key.v2(), barycentric);
                float localU = clampUnit((atlasU - sprite.getU0()) / (sprite.getU1() - sprite.getU0()));
                float localV = clampUnit((atlasV - sprite.getV0()) / (sprite.getV1() - sprite.getV0()));
                int pixelX = Math.min((int) (localU * contents.width()), contents.width() - 1);
                int pixelY = Math.min((int) (localV * contents.height()), contents.height() - 1);
                float maximum = 0.0F;
                for (int frame : frames) {
                    int sourceX = frame % columns * contents.width() + pixelX;
                    int sourceY = frame / columns * contents.height() + pixelY;
                    int argb = image.getPixel(sourceX, sourceY);
                    int alpha = argb >>> 24;
                    if (key.cutout && alpha < 128) {
                        continue;
                    }
                    float red = decodeSrgb((argb >>> 16 & 0xff) / 255.0F) * tint[0];
                    float green = decodeSrgb((argb >>> 8 & 0xff) / 255.0F) * tint[1];
                    float blue = decodeSrgb((argb & 0xff) / 255.0F) * tint[2];
                    // A D65 linear-sRGB -> Rec.2020 conversion preserves CIE Y, so these
                    // coefficients are also the exact importance luminance of the working color.
                    maximum = Math.max(maximum, 0.2126F * red + 0.7152F * green + 0.0722F * blue);
                }
                total += maximum;
            }
            weights[cellIndex] = total / STRATIFIED_SAMPLE_COUNT;
        }
    }

    private static int[] frames(SpriteContents contents) {
        if (!contents.isAnimated()) {
            return new int[] {0};
        }
        IntList unique = contents.getUniqueFrames();
        return unique.toIntArray();
    }

    private static float[] linearTint(int argb) {
        return new float[] {
            decodeSrgb((argb >>> 16 & 0xff) / 255.0F),
            decodeSrgb((argb >>> 8 & 0xff) / 255.0F),
            decodeSrgb((argb & 0xff) / 255.0F)
        };
    }

    private static float decodeSrgb(float value) {
        return value <= 0.04045F
                ? value / 12.92F
                : (float) Math.pow((value + 0.055F) / 1.055F, 2.4);
    }

    private static float interpolate(float first, float second, float third, float[] barycentric) {
        return first * barycentric[0] + second * barycentric[1] + third * barycentric[2];
    }

    private static float clampUnit(float value) {
        return Math.max(0.0F, Math.min(Math.nextDown(1.0F), value));
    }

    private static void buildAliasTable(
            float[] weights,
            double sum,
            float[] probabilities,
            int[] aliases,
            float[] masses) {
        double[] scaled = new double[CELL_COUNT];
        int[] small = new int[CELL_COUNT];
        int[] large = new int[CELL_COUNT];
        int smallSize = 0;
        int largeSize = 0;
        for (int index = 0; index < CELL_COUNT; index++) {
            masses[index] = (float) (weights[index] / sum);
            scaled[index] = weights[index] * CELL_COUNT / sum;
            if (scaled[index] < 1.0) {
                small[smallSize++] = index;
            } else {
                large[largeSize++] = index;
            }
        }
        while (smallSize != 0 && largeSize != 0) {
            int smallIndex = small[--smallSize];
            int largeIndex = large[--largeSize];
            probabilities[smallIndex] = (float) scaled[smallIndex];
            aliases[smallIndex] = largeIndex;
            scaled[largeIndex] = scaled[largeIndex] + scaled[smallIndex] - 1.0;
            if (scaled[largeIndex] < 1.0) {
                small[smallSize++] = largeIndex;
            } else {
                large[largeSize++] = largeIndex;
            }
        }
        while (largeSize != 0) {
            int index = large[--largeSize];
            probabilities[index] = 1.0F;
            aliases[index] = index;
        }
        while (smallSize != 0) {
            int index = small[--smallSize];
            probabilities[index] = 1.0F;
            aliases[index] = index;
        }
    }

    static Cell cell(int index) {
        if (index < 0 || index >= CELL_COUNT) {
            throw new IndexOutOfBoundsException(index);
        }
        int remaining = index;
        for (int row = 0; row < SUBDIVISION; row++) {
            int rowCount = 2 * (SUBDIVISION - row) - 1;
            if (remaining < rowCount) {
                int column = remaining / 2;
                boolean upper = (remaining & 1) != 0;
                return new Cell(column, row, upper);
            }
            remaining -= rowCount;
        }
        throw new IllegalStateException("Emission cell index mapping failed");
    }

    float aliasProbability(int index) {
        return this.aliasProbabilities[index];
    }

    int alias(int index) {
        return this.aliases[index];
    }

    float probabilityMass(int index) {
        return this.probabilityMasses[index];
    }

    float meanLuminance() {
        return this.meanLuminance;
    }

    record Key(TextureAtlasSprite sprite, int packedUv0, int packedUv1, int packedUv2, int tintArgb, boolean cutout) {
        float u0() {
            return unpackLow(this.packedUv0);
        }

        float v0() {
            return unpackHigh(this.packedUv0);
        }

        float u1() {
            return unpackLow(this.packedUv1);
        }

        float v1() {
            return unpackHigh(this.packedUv1);
        }

        float u2() {
            return unpackLow(this.packedUv2);
        }

        float v2() {
            return unpackHigh(this.packedUv2);
        }

        private static float unpackLow(int packed) {
            return Float.float16ToFloat((short) (packed & 0xffff));
        }

        private static float unpackHigh(int packed) {
            return Float.float16ToFloat((short) (packed >>> 16));
        }
    }

    record Cell(int column, int row, boolean upper) {
        float[][] vertices() {
            float inverse = 1.0F / SUBDIVISION;
            float x = this.column * inverse;
            float y = this.row * inverse;
            if (this.upper) {
                return new float[][] {
                    {1.0F - x - inverse - y, x + inverse, y},
                    {1.0F - x - inverse - y - inverse, x + inverse, y + inverse},
                    {1.0F - x - y - inverse, x, y + inverse}
                };
            }
            return new float[][] {
                {1.0F - x - y, x, y},
                {1.0F - x - inverse - y, x + inverse, y},
                {1.0F - x - y - inverse, x, y + inverse}
            };
        }

        float[] samplePoint(int sampleIndex) {
            float[][] vertices = this.vertices();
            float[] centroid = new float[] {
                (vertices[0][0] + vertices[1][0] + vertices[2][0]) / 3.0F,
                (vertices[0][1] + vertices[1][1] + vertices[2][1]) / 3.0F,
                (vertices[0][2] + vertices[1][2] + vertices[2][2]) / 3.0F
            };
            if (sampleIndex == 0) {
                return centroid;
            }
            float[] vertex = vertices[sampleIndex - 1];
            return new float[] {
                (centroid[0] + vertex[0]) * 0.5F,
                (centroid[1] + vertex[1]) * 0.5F,
                (centroid[2] + vertex[2]) * 0.5F
            };
        }
    }
}
