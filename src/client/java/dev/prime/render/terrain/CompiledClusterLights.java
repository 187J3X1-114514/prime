package dev.prime.render.terrain;

import dev.prime.render.shader.ShaderAbi;
import java.util.Objects;

/**
 * Relocatable final light payload produced by cluster compilation.
 *
 * <p>The first five ABI fields are byte offsets when stored here. Upload adds the destination
 * device address without rebuilding emitters, distributions, or either light-tree stream.
 */
public final class CompiledClusterLights {
    private static final int POINTER_COUNT = 5;
    private static final int HEADER_WORDS = 12;

    public static final CompiledClusterLights EMPTY =
            new CompiledClusterLights(new int[0], Summary.EMPTY);

    private final int[] relativeWords;
    private final Summary summary;

    private CompiledClusterLights(int[] relativeWords, Summary summary) {
        this.relativeWords = relativeWords;
        this.summary = summary;
    }

    static CompiledClusterLights compile(CpuSectionLights source) {
        Objects.requireNonNull(source, "source");
        if (source.isEmpty()) {
            return EMPTY;
        }
        CpuSectionLights.Summary sourceSummary = source.summary();
        return new CompiledClusterLights(
                source.pack(0L),
                new Summary(
                        sourceSummary.emitterCount(),
                        sourceSummary.bounds().minX(),
                        sourceSummary.bounds().minY(),
                        sourceSummary.bounds().minZ(),
                        sourceSummary.bounds().maxX(),
                        sourceSummary.bounds().maxY(),
                        sourceSummary.bounds().maxZ(),
                        sourceSummary.power()));
    }

    static CompiledClusterLights fromEncoded(int[] relativeWords, Summary summary) {
        Objects.requireNonNull(relativeWords, "relativeWords");
        Objects.requireNonNull(summary, "summary");
        if (summary.isEmpty()) {
            if (relativeWords.length != 0) {
                throw new IllegalArgumentException(
                        "Empty compiled lights must not contain an encoded payload");
            }
            return EMPTY;
        }
        if (relativeWords.length < HEADER_WORDS) {
            throw new IllegalArgumentException("Compiled light payload is smaller than its header");
        }
        int byteSize = Math.multiplyExact(relativeWords.length, Integer.BYTES);
        long[] offsets = new long[POINTER_COUNT];
        for (int pointer = 0; pointer < POINTER_COUNT; pointer++) {
            long offset = getLong(relativeWords, pointer * 2);
            if (offset < 0L || offset > byteSize || (offset & 3L) != 0L) {
                throw new IllegalArgumentException(
                        "Compiled light payload contains an invalid relative pointer");
            }
            offsets[pointer] = offset;
        }
        if (relativeWords[11] != summary.emitterCount()) {
            throw new IllegalArgumentException(
                    "Compiled light header disagrees with its emitter summary");
        }
        validateLayout(relativeWords, offsets, byteSize, summary.emitterCount());
        return new CompiledClusterLights(relativeWords.clone(), summary);
    }

    public boolean isEmpty() {
        return this.summary.isEmpty();
    }

    public int emitterCount() {
        return this.summary.emitterCount();
    }

    public long byteSize() {
        return (long) this.relativeWords.length * Integer.BYTES;
    }

    public Summary summary() {
        return this.summary;
    }

    /** Returns the canonical zero-base ABI words for hashing or replay serialization. */
    public int[] encodedWords() {
        return this.relativeWords.clone();
    }

    /** Returns one owned upload payload relocated to {@code deviceAddress}. */
    public int[] relocate(long deviceAddress) {
        if (this.isEmpty()) {
            return new int[0];
        }
        if (deviceAddress == 0L) {
            return this.encodedWords();
        }
        int[] relocated = this.relativeWords.clone();
        for (int pointer = 0; pointer < POINTER_COUNT; pointer++) {
            int word = pointer * 2;
            putLong(
                    relocated,
                    word,
                    Math.addExact(deviceAddress, getLong(relocated, word)));
        }
        return relocated;
    }

    private static long getLong(int[] words, int offset) {
        return Integer.toUnsignedLong(words[offset])
                | (long) words[offset + 1] << 32;
    }

    private static void putLong(int[] words, int offset, long value) {
        words[offset] = (int) value;
        words[offset + 1] = (int) (value >>> 32);
    }

    private static void validateLayout(
            int[] words, long[] offsets, int byteSize, int emitterCount) {
        long nodeStart = offsets[0];
        long forwardStart = offsets[1];
        long reverseStart = offsets[2];
        long emitterStart = offsets[3];
        long cellStart = offsets[4];
        long headerBytes = (long) HEADER_WORDS * Integer.BYTES;
        if (words[10] != 0
                || nodeStart != headerBytes
                || forwardStart < nodeStart
                || reverseStart < forwardStart
                || emitterStart < reverseStart
                || cellStart < emitterStart) {
            throw new IllegalArgumentException(
                    "Compiled light payload has an invalid section order");
        }
        long nodeBytes = forwardStart - nodeStart;
        if (nodeBytes % ShaderAbi.LIGHT_NODE_SIZE != 0L) {
            throw new IllegalArgumentException(
                    "Compiled light node stream is misaligned");
        }
        long nodeCount = nodeBytes / ShaderAbi.LIGHT_NODE_SIZE;
        long expectedNodeCount = Math.subtractExact(
                Math.multiplyExact((long) emitterCount, 2L), 1L);
        long expectedReverse = Math.addExact(
                forwardStart,
                Math.multiplyExact(
                        nodeCount, ShaderAbi.LIGHT_NODE_FORWARD_SIZE));
        long reverseEnd = Math.addExact(
                reverseStart,
                Math.multiplyExact(
                        nodeCount, ShaderAbi.LIGHT_NODE_REVERSE_SIZE));
        long expectedEmitter = alignUp(reverseEnd, 16L);
        long expectedCells = Math.addExact(
                emitterStart,
                Math.multiplyExact(
                        (long) emitterCount, ShaderAbi.LIGHT_EMITTER_SIZE));
        long distributionBytes = Math.multiplyExact(
                (long) EmissionDistribution.CELL_COUNT,
                ShaderAbi.LIGHT_CELL_SIZE);
        long distributionCount = (byteSize - cellStart) / distributionBytes;
        if (reverseStart != expectedReverse
                || emitterStart != expectedEmitter
                || cellStart != expectedCells
                || (byteSize - cellStart) % distributionBytes != 0L
                || nodeCount != expectedNodeCount
                || distributionCount == 0L) {
            throw new IllegalArgumentException(
                    "Compiled light payload disagrees with the shader ABI");
        }
        validateTreeAndEmitterReferences(
                words,
                forwardStart,
                reverseStart,
                emitterStart,
                nodeCount,
                emitterCount,
                distributionCount);
    }

    private static void validateTreeAndEmitterReferences(
            int[] words,
            long forwardStart,
            long reverseStart,
            long emitterStart,
            long nodeCount,
            int emitterCount,
            long distributionCount) {
        int forwardWord = Math.toIntExact(forwardStart / Integer.BYTES);
        int reverseWord = Math.toIntExact(reverseStart / Integer.BYTES);
        for (int node = 0; node < nodeCount; node++) {
            int childOrLeaf = words[forwardWord + node];
            int parent = words[reverseWord + node];
            if ((childOrLeaf & CpuLightTree.LEAF_FLAG) != 0) {
                if ((childOrLeaf & CpuLightTree.INDEX_MASK) >= emitterCount) {
                    throw new IllegalArgumentException(
                            "Compiled light tree contains an invalid leaf");
                }
            } else if (childOrLeaf < 0
                    || childOrLeaf + 1L >= nodeCount
                    || (childOrLeaf & 1) == 0
                    || words[reverseWord + childOrLeaf] != node
                    || words[reverseWord + childOrLeaf + 1] != node) {
                throw new IllegalArgumentException(
                        "Compiled light tree contains invalid children");
            }
            if (node == 0) {
                if (parent != CpuLightTree.NO_INDEX) {
                    throw new IllegalArgumentException(
                            "Compiled light tree root has a parent");
                }
            } else if (parent < 0 || parent >= nodeCount) {
                throw new IllegalArgumentException(
                        "Compiled light tree contains an invalid parent");
            }
        }

        int emitterWords = ShaderAbi.LIGHT_EMITTER_SIZE / Integer.BYTES;
        int metadataWord =
                ShaderAbi.LIGHT_EMITTER_METADATA_OFFSET / Integer.BYTES;
        int emitterWord = Math.toIntExact(emitterStart / Integer.BYTES);
        for (int emitter = 0; emitter < emitterCount; emitter++) {
            int metadata = emitterWord + emitter * emitterWords + metadataWord;
            long firstCell = Integer.toUnsignedLong(words[metadata]);
            long leafNode = Integer.toUnsignedLong(words[metadata + 1]);
            if (firstCell % EmissionDistribution.CELL_COUNT != 0L
                    || firstCell / EmissionDistribution.CELL_COUNT
                            >= distributionCount
                    || leafNode >= nodeCount
                    || (words[forwardWord + (int) leafNode]
                                    & CpuLightTree.LEAF_FLAG)
                            == 0
                    || (words[forwardWord + (int) leafNode]
                                    & CpuLightTree.INDEX_MASK)
                            != emitter) {
                throw new IllegalArgumentException(
                        "Compiled light emitter references invalid tree or distribution data");
            }
        }
    }

    private static long alignUp(long value, long alignment) {
        return Math.addExact(value, alignment - 1L) / alignment * alignment;
    }

    public record Summary(
            int emitterCount,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            float power) {
        private static final Summary EMPTY =
                new Summary(0, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);

        public Summary {
            if (emitterCount < 0) {
                throw new IllegalArgumentException("Emitter count must not be negative");
            }
            if (!Float.isFinite(minX)
                    || !Float.isFinite(minY)
                    || !Float.isFinite(minZ)
                    || !Float.isFinite(maxX)
                    || !Float.isFinite(maxY)
                    || !Float.isFinite(maxZ)
                    || !Float.isFinite(power)) {
                throw new IllegalArgumentException("Compiled light summary must be finite");
            }
            if (emitterCount == 0) {
                if (power != 0.0F) {
                    throw new IllegalArgumentException(
                            "Empty compiled lights must have zero power");
                }
            } else if (!(power > 0.0F)
                    || minX > maxX
                    || minY > maxY
                    || minZ > maxZ) {
                throw new IllegalArgumentException("Compiled light summary is inconsistent");
            }
        }

        public boolean isEmpty() {
            return this.emitterCount == 0;
        }

        CpuLightTree.Bounds bounds() {
            if (this.isEmpty()) {
                throw new IllegalStateException("Empty compiled lights have no bounds");
            }
            return new CpuLightTree.Bounds(
                    this.minX,
                    this.minY,
                    this.minZ,
                    this.maxX,
                    this.maxY,
                    this.maxZ);
        }
    }
}
