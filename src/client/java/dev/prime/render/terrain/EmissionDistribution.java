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
    private static final float MINIMUM_CELL_IMPORTANCE = 1.0E-5F;
    private static final int STRATIFIED_SAMPLE_COUNT = 4;

    private final float[] aliasProbabilities;
    private final int[] aliases;
    private final float[] probabilityMasses;
    private final float meanImportance;

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
        this.meanImportance = (float) (sum / CELL_COUNT);
        buildAliasTable(weights, sum, this.aliasProbabilities, this.aliases, this.probabilityMasses);
    }

    static EmissionDistribution build(Key key) {
        float[] weights = new float[CELL_COUNT];
        try {
            fillTextureImportance(key, weights);
        } catch (RuntimeException exception) {
            // A resource reload can retire SpriteContents while an already-cancelled mesh job is
            // winding down. Uniform support is still unbiased because radiance is read from the
            // live atlas; it only loses the variance reduction of the static importance table.
            java.util.Arrays.fill(weights, 1.0F);
        }
        for (int index = 0; index < weights.length; index++) {
            weights[index] = Math.max(weights[index], MINIMUM_CELL_IMPORTANCE);
        }
        return new EmissionDistribution(weights);
    }

    static EmissionDistribution uniform() {
        float[] weights = new float[CELL_COUNT];
        java.util.Arrays.fill(weights, 1.0F);
        return new EmissionDistribution(weights);
    }

    private static void fillTextureImportance(Key key, float[] weights) {
        TextureAtlasSprite sprite = key.sprite;
        SpriteContents contents = sprite.contents();
        NativeImage image = ((SpriteContentsAccessor) (Object) contents).prime$originalImage();
        int[] frames = frames(contents);
        int columns = Math.max(image.getWidth() / contents.width(), 1);
        float[] tint = linearTint(key.tintArgb);
        float[] barycentric = new float[3];
        for (int cellIndex = 0; cellIndex < CELL_COUNT; cellIndex++) {
            Cell cell = cell(cellIndex);
            float total = 0.0F;
            for (int sample = 0; sample < STRATIFIED_SAMPLE_COUNT; sample++) {
                cell.samplePoint(sample, barycentric);
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
                    // Importance uses the largest component in Prime's actual linear Rec.2020
                    // working space. This controls variance only: emitted RGB and its PDF remain
                    // separate, so changing this proxy cannot bias the estimator.
                    maximum = Math.max(maximum, linearSrgbToRec2020Maximum(red, green, blue));
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

    static float linearSrgbToRec2020Maximum(float red, float green, float blue) {
        // Keep these coefficients identical to primeLinearSrgbToLinearRec2020 in color_space.glsl.
        float rec2020Red = 0.6274039F * red + 0.3292830F * green + 0.0433131F * blue;
        float rec2020Green = 0.0690973F * red + 0.9195404F * green + 0.0113623F * blue;
        float rec2020Blue = 0.0163914F * red + 0.0880133F * green + 0.8955953F * blue;
        return Math.max(rec2020Red, Math.max(rec2020Green, rec2020Blue));
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

    float meanImportance() {
        return this.meanImportance;
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
        int packedGeometry() {
            return this.column | this.row << 4 | (this.upper ? 1 << 8 : 0);
        }

        float[][] vertices() {
            float inverse = 1.0F / SUBDIVISION;
            float x = this.column * inverse;
            float y = this.row * inverse;
            if (this.upper) {
                return new float[][] {
                    {1.0F - x - inverse - y, x + inverse, y},
                    {1.0F - x - 2.0F * inverse - y, x + inverse, y + inverse},
                    {1.0F - x - y - inverse, x, y + inverse}
                };
            }
            return new float[][] {
                {1.0F - x - y, x, y},
                {1.0F - x - inverse - y, x + inverse, y},
                {1.0F - x - y - inverse, x, y + inverse}
            };
        }

        void samplePoint(int sampleIndex, float[] target) {
            if (sampleIndex < 0 || sampleIndex >= STRATIFIED_SAMPLE_COUNT) {
                throw new IndexOutOfBoundsException(sampleIndex);
            }
            if (target.length < 3) {
                throw new IllegalArgumentException("Barycentric output must contain three elements");
            }
            float inverse = 1.0F / SUBDIVISION;
            float x = this.column * inverse;
            float y = this.row * inverse;
            float centroid0;
            float centroid1;
            float centroid2;
            if (this.upper) {
                centroid0 = 1.0F - x - y - 4.0F * inverse / 3.0F;
                centroid1 = x + 2.0F * inverse / 3.0F;
                centroid2 = y + 2.0F * inverse / 3.0F;
            } else {
                centroid0 = 1.0F - x - y - 2.0F * inverse / 3.0F;
                centroid1 = x + inverse / 3.0F;
                centroid2 = y + inverse / 3.0F;
            }
            if (sampleIndex == 0) {
                target[0] = centroid0;
                target[1] = centroid1;
                target[2] = centroid2;
                return;
            }
            int vertex = sampleIndex - 1;
            float vertex1;
            float vertex2;
            if (this.upper) {
                vertex1 = vertex < 2 ? x + inverse : x;
                vertex2 = vertex == 0 ? y : y + inverse;
            } else {
                vertex1 = vertex == 1 ? x + inverse : x;
                vertex2 = vertex == 2 ? y + inverse : y;
            }
            float vertex0 = 1.0F - vertex1 - vertex2;
            target[0] = (centroid0 + vertex0) * 0.5F;
            target[1] = (centroid1 + vertex1) * 0.5F;
            target[2] = (centroid2 + vertex2) * 0.5F;
        }
    }
}
