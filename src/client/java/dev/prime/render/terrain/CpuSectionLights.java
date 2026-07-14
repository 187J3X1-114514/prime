package dev.prime.render.terrain;

import dev.prime.render.shader.ShaderAbi;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** Immutable light surfaces, local tree and shared texture distributions for one Section. */
public final class CpuSectionLights {
    public static final CpuSectionLights EMPTY = new CpuSectionLights();
    static final int EMITTER_FLAG_TWO_SIDED = 1;

    private final List<Emitter> emitters;
    private final List<EmissionDistribution> distributions;
    private final CpuLightTree.Result tree;

    private CpuSectionLights() {
        this.emitters = List.of();
        this.distributions = List.of();
        this.tree = null;
    }

    private CpuSectionLights(
            List<Emitter> emitters,
            List<EmissionDistribution> distributions,
            CpuLightTree.Result tree) {
        this.emitters = List.copyOf(emitters);
        this.distributions = List.copyOf(distributions);
        this.tree = tree;
    }

    public boolean isEmpty() {
        return this.emitters.isEmpty();
    }

    public int emitterCount() {
        return this.emitters.size();
    }

    public long byteSize() {
        if (this.isEmpty()) {
            return 0L;
        }
        return ShaderAbi.SECTION_LIGHT_HEADER_SIZE
                + (long) this.tree.nodeCount() * ShaderAbi.LIGHT_NODE_SIZE
                + (long) this.emitters.size() * ShaderAbi.LIGHT_EMITTER_SIZE
                + (long) this.distributions.size()
                        * EmissionDistribution.CELL_COUNT
                        * ShaderAbi.LIGHT_CELL_SIZE;
    }

    public int[] pack(long bufferAddress) {
        if (this.isEmpty()) {
            return new int[0];
        }
        int headerWords = ShaderAbi.SECTION_LIGHT_HEADER_SIZE / Integer.BYTES;
        int[] nodeWords = this.tree.packNodes();
        int emitterWords = ShaderAbi.LIGHT_EMITTER_SIZE / Integer.BYTES;
        int cellWords = ShaderAbi.LIGHT_CELL_SIZE / Integer.BYTES;
        int cellCount = this.distributions.size() * EmissionDistribution.CELL_COUNT;
        int[] result = new int[headerWords + nodeWords.length
                + this.emitters.size() * emitterWords + cellCount * cellWords];
        int nodeStart = headerWords;
        int emitterStart = nodeStart + nodeWords.length;
        int cellStart = emitterStart + this.emitters.size() * emitterWords;
        putLong(result, 0, bufferAddress + (long) nodeStart * Integer.BYTES);
        putLong(result, 2, bufferAddress + (long) emitterStart * Integer.BYTES);
        putLong(result, 4, bufferAddress + (long) cellStart * Integer.BYTES);
        result[6] = 0;
        result[7] = this.emitters.size();
        System.arraycopy(nodeWords, 0, result, nodeStart, nodeWords.length);

        for (int index = 0; index < this.emitters.size(); index++) {
            Emitter emitter = this.emitters.get(index);
            int cursor = emitterStart + index * emitterWords;
            putFloat(result, cursor, emitter.cornerX);
            putFloat(result, cursor + 1, emitter.cornerY);
            putFloat(result, cursor + 2, emitter.cornerZ);
            putFloat(result, cursor + 3, emitter.area);
            putFloat(result, cursor + 4, emitter.edgeOneX);
            putFloat(result, cursor + 5, emitter.edgeOneY);
            putFloat(result, cursor + 6, emitter.edgeOneZ);
            putFloat(result, cursor + 7, emitter.emissionScale);
            putFloat(result, cursor + 8, emitter.edgeTwoX);
            putFloat(result, cursor + 9, emitter.edgeTwoY);
            putFloat(result, cursor + 10, emitter.edgeTwoZ);
            putFloat(result, cursor + 11, emitter.power);
            putFloat(result, cursor + 12, emitter.normalX);
            putFloat(result, cursor + 13, emitter.normalY);
            putFloat(result, cursor + 14, emitter.normalZ);
            result[cursor + 15] = 0;
            result[cursor + 16] = emitter.packedUv0;
            result[cursor + 17] = emitter.packedUv1;
            result[cursor + 18] = emitter.packedUv2;
            result[cursor + 19] = emitter.packedTint;
            result[cursor + 20] = emitter.distributionIndex * EmissionDistribution.CELL_COUNT;
            result[cursor + 21] = this.tree.leafNode(index);
            result[cursor + 22] = emitter.flags;
            result[cursor + 23] = 0;
        }

        for (int distributionIndex = 0; distributionIndex < this.distributions.size(); distributionIndex++) {
            EmissionDistribution distribution = this.distributions.get(distributionIndex);
            for (int cell = 0; cell < EmissionDistribution.CELL_COUNT; cell++) {
                int cursor = cellStart
                        + (distributionIndex * EmissionDistribution.CELL_COUNT + cell) * cellWords;
                putFloat(result, cursor, distribution.aliasProbability(cell));
                result[cursor + 1] = distribution.alias(cell);
                putFloat(result, cursor + 2, distribution.probabilityMass(cell));
                result[cursor + 3] = EmissionDistribution.cell(cell).packedGeometry();
            }
        }
        return result;
    }

    CpuLightTree.Bounds bounds() {
        if (this.isEmpty()) {
            throw new IllegalStateException("Empty Section has no light bounds");
        }
        return this.tree.bounds();
    }

    float power() {
        return this.isEmpty() ? 0.0F : this.tree.power();
    }

    private static void putLong(int[] target, int wordOffset, long value) {
        target[wordOffset] = (int) value;
        target[wordOffset + 1] = (int) (value >>> 32);
    }

    private static void putFloat(int[] target, int wordOffset, float value) {
        target[wordOffset] = Float.floatToRawIntBits(value);
    }

    public static final class Builder {
        // 1024 * 256 * 16 bytes = 4 MiB worst-case importance data per Section. Additional
        // layouts share a uniform full-support distribution, preserving correctness under
        // pathological resource packs instead of overflowing the bounded staging arena.
        private static final int MAXIMUM_IMPORTANCE_DISTRIBUTIONS = 1024;

        private final List<Emitter> emitters = new ArrayList<>();
        private final Map<EmissionDistribution.Key, Integer> distributionIndices = new LinkedHashMap<>();
        private final List<EmissionDistribution> distributions = new ArrayList<>();
        private int uniformDistributionIndex = -1;

        public int addTriangle(
                float cornerX,
                float cornerY,
                float cornerZ,
                float secondX,
                float secondY,
                float secondZ,
                float thirdX,
                float thirdY,
                float thirdZ,
                int packedUv0,
                int packedUv1,
                int packedUv2,
                int tintArgb,
                int packedTint,
                boolean cutout,
                int lightEmission,
                TextureAtlasSprite sprite) {
            if (lightEmission <= 0) {
                return 0;
            }
            float edgeOneX = secondX - cornerX;
            float edgeOneY = secondY - cornerY;
            float edgeOneZ = secondZ - cornerZ;
            float edgeTwoX = thirdX - cornerX;
            float edgeTwoY = thirdY - cornerY;
            float edgeTwoZ = thirdZ - cornerZ;
            float normalX = edgeOneY * edgeTwoZ - edgeOneZ * edgeTwoY;
            float normalY = edgeOneZ * edgeTwoX - edgeOneX * edgeTwoZ;
            float normalZ = edgeOneX * edgeTwoY - edgeOneY * edgeTwoX;
            float twiceArea = (float) Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
            if (!(twiceArea > 1.0E-8F) || !Float.isFinite(twiceArea)) {
                return 0;
            }
            float inverseLength = 1.0F / twiceArea;
            normalX *= inverseLength;
            normalY *= inverseLength;
            normalZ *= inverseLength;
            float area = 0.5F * twiceArea;
            EmissionDistribution.Key key = new EmissionDistribution.Key(
                    sprite, packedUv0, packedUv1, packedUv2, tintArgb, cutout);
            Integer cachedDistribution = this.distributionIndices.get(key);
            int distributionIndex;
            if (cachedDistribution != null) {
                distributionIndex = cachedDistribution;
            } else if (this.distributionIndices.size() < MAXIMUM_IMPORTANCE_DISTRIBUTIONS) {
                distributionIndex = this.distributions.size();
                this.distributions.add(EmissionDistribution.build(key));
                this.distributionIndices.put(key, distributionIndex);
            } else {
                if (this.uniformDistributionIndex < 0) {
                    this.uniformDistributionIndex = this.distributions.size();
                    this.distributions.add(EmissionDistribution.uniform());
                }
                distributionIndex = this.uniformDistributionIndex;
            }
            EmissionDistribution distribution = this.distributions.get(distributionIndex);
            float scale = emissionScale(lightEmission);
            int flags = cutout ? EMITTER_FLAG_TWO_SIDED : 0;
            float sidedness = cutout ? 2.0F : 1.0F;
            float power = area * (float) Math.PI * sidedness * scale * distribution.meanImportance();
            if (!(power > 0.0F) || !Float.isFinite(power)) {
                return 0;
            }
            int index = this.emitters.size();
            this.emitters.add(new Emitter(
                    cornerX,
                    cornerY,
                    cornerZ,
                    edgeOneX,
                    edgeOneY,
                    edgeOneZ,
                    edgeTwoX,
                    edgeTwoY,
                    edgeTwoZ,
                    normalX,
                    normalY,
                    normalZ,
                    area,
                    scale,
                    power,
                    packedUv0,
                    packedUv1,
                    packedUv2,
                    packedTint,
                    distributionIndex,
                    flags));
            return index + 1;
        }

        public CpuSectionLights build() {
            if (this.emitters.isEmpty()) {
                return EMPTY;
            }
            List<CpuLightTree.Leaf> leaves = new ArrayList<>(this.emitters.size());
            for (int index = 0; index < this.emitters.size(); index++) {
                Emitter emitter = this.emitters.get(index);
                CpuLightTree.Bounds bounds = CpuLightTree.Bounds.empty()
                        .include(emitter.cornerX, emitter.cornerY, emitter.cornerZ)
                        .include(
                                emitter.cornerX + emitter.edgeOneX,
                                emitter.cornerY + emitter.edgeOneY,
                                emitter.cornerZ + emitter.edgeOneZ)
                        .include(
                                emitter.cornerX + emitter.edgeTwoX,
                                emitter.cornerY + emitter.edgeTwoY,
                                emitter.cornerZ + emitter.edgeTwoZ);
                leaves.add(new CpuLightTree.Leaf(
                        bounds,
                        (bounds.minX() + bounds.maxX()) * 0.5F,
                        (bounds.minY() + bounds.maxY()) * 0.5F,
                        (bounds.minZ() + bounds.maxZ()) * 0.5F,
                        emitter.power,
                        index));
            }
            CpuLightTree.Result tree = CpuLightTree.build(
                    leaves, this.emitters.size(), CpuLightTree.SECTION_SOFTENING_SCALE);
            return new CpuSectionLights(this.emitters, this.distributions, tree);
        }
    }

    /** Prime's default source-radiance calibration: a white level-15 texel evaluates to 15. */
    static float emissionScale(int level) {
        int clamped = Math.max(0, Math.min(level, 15));
        return (float) clamped * clamped / 15.0F;
    }

    private record Emitter(
            float cornerX,
            float cornerY,
            float cornerZ,
            float edgeOneX,
            float edgeOneY,
            float edgeOneZ,
            float edgeTwoX,
            float edgeTwoY,
            float edgeTwoZ,
            float normalX,
            float normalY,
            float normalZ,
            float area,
            float emissionScale,
            float power,
            int packedUv0,
            int packedUv1,
            int packedUv2,
            int packedTint,
            int distributionIndex,
            int flags) {
    }
}
