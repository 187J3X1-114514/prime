package dev.prime.render.terrain;

import dev.prime.render.shader.ShaderAbi;
import java.util.Objects;

/**
 * Relocatable final light payload produced by cluster compilation.
 *
 * <p>The first five ABI fields are byte offsets when stored here. Upload adds the destination
 * device address without rebuilding emitters, distributions, or light-tree records.
 */
public final class CompiledClusterLights {
    private static final int POINTER_COUNT = 5;
    private static final int HEADER_WORDS = 12;
    private static final int LEGACY_LIGHT_NODE_SIZE = 32;
    private static final int LEGACY_LIGHT_NODE_FORWARD_SIZE = 8;
    private static final int LEGACY_LIGHT_NODE_REVERSE_SIZE = 4;

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
                        sourceSummary.power(),
                        sourceSummary.packedDirection()));
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
        validateLayout(
                relativeWords,
                offsets,
                byteSize,
                summary.emitterCount(),
                summary.packedDirection());
        return new CompiledClusterLights(relativeWords.clone(), summary);
    }

    /** Upgrades the pre-v6 one-word forward stream with conservative full-direction metadata. */
    static int[] addFullDirectionStream(int[] oldWords) {
        Objects.requireNonNull(oldWords, "oldWords");
        if (oldWords.length < HEADER_WORDS) {
            throw new IllegalArgumentException("Compiled light payload is smaller than its header");
        }
        long nodeStart = getLong(oldWords, 0);
        long forwardStart = getLong(oldWords, 2);
        long reverseStart = getLong(oldWords, 4);
        long emitterStart = getLong(oldWords, 6);
        long cellStart = getLong(oldWords, 8);
        long byteSize = (long) oldWords.length * Integer.BYTES;
        if (nodeStart < 0L
                || forwardStart < 0L
                || reverseStart < 0L
                || emitterStart < 0L
                || cellStart < 0L
                || (nodeStart | forwardStart | reverseStart | emitterStart | cellStart) % 4L != 0L
                || nodeStart != (long) HEADER_WORDS * Integer.BYTES
                || forwardStart < nodeStart
                || emitterStart < reverseStart
                || cellStart < emitterStart
                || cellStart > byteSize
                || (forwardStart - nodeStart) % LEGACY_LIGHT_NODE_SIZE != 0L) {
            throw new IllegalArgumentException("Legacy compiled light payload is inconsistent");
        }
        long nodeCount = (forwardStart - nodeStart) / LEGACY_LIGHT_NODE_SIZE;
        long oldReverseStart = Math.addExact(
                forwardStart, Math.multiplyExact(nodeCount, Integer.BYTES));
        long oldReverseEnd = Math.addExact(
                reverseStart, Math.multiplyExact(nodeCount, Integer.BYTES));
        if (reverseStart != oldReverseStart || emitterStart != alignUp(oldReverseEnd, 16L)) {
            throw new IllegalArgumentException("Legacy compiled light streams disagree");
        }
        long newReverseStart = Math.addExact(
                forwardStart,
                Math.multiplyExact(nodeCount, LEGACY_LIGHT_NODE_FORWARD_SIZE));
        long newReverseEnd = Math.addExact(
                newReverseStart,
                Math.multiplyExact(nodeCount, LEGACY_LIGHT_NODE_REVERSE_SIZE));
        long newEmitterStart = alignUp(newReverseEnd, 16L);
        long emitterShift = newEmitterStart - emitterStart;
        long newCellStart = Math.addExact(cellStart, emitterShift);
        int newWordCount = Math.toIntExact(
                Math.addExact(byteSize, emitterShift) / Integer.BYTES);
        int[] upgraded = new int[newWordCount];
        int forwardWord = Math.toIntExact(forwardStart / Integer.BYTES);
        System.arraycopy(oldWords, 0, upgraded, 0, forwardWord);
        int nodeCountInt = Math.toIntExact(nodeCount);
        for (int node = 0; node < nodeCountInt; node++) {
            upgraded[forwardWord + node * 2] = oldWords[forwardWord + node];
            upgraded[forwardWord + node * 2 + 1] = LightDirection.FULL;
        }
        int oldReverseWord = Math.toIntExact(reverseStart / Integer.BYTES);
        int newReverseWord = Math.toIntExact(newReverseStart / Integer.BYTES);
        System.arraycopy(oldWords, oldReverseWord, upgraded, newReverseWord, nodeCountInt);
        int oldEmitterWord = Math.toIntExact(emitterStart / Integer.BYTES);
        int newEmitterWord = Math.toIntExact(newEmitterStart / Integer.BYTES);
        System.arraycopy(
                oldWords,
                oldEmitterWord,
                upgraded,
                newEmitterWord,
                oldWords.length - oldEmitterWord);
        putLong(upgraded, 4, newReverseStart);
        putLong(upgraded, 6, newEmitterStart);
        putLong(upgraded, 8, newCellStart);
        return upgraded;
    }

    /** Converts pre-v7 emitter UV words from FP16 to the fixed atlas-coordinate encoding. */
    static int[] upgradeUvPacking(int[] oldWords, int emitterCount) {
        Objects.requireNonNull(oldWords, "oldWords");
        if (emitterCount <= 0 || oldWords.length < HEADER_WORDS) {
            throw new IllegalArgumentException("Legacy compiled light payload is inconsistent");
        }
        long emitterStart = getLong(oldWords, 6);
        long emitterEnd = Math.addExact(
                emitterStart,
                Math.multiplyExact((long) emitterCount, ShaderAbi.LIGHT_EMITTER_SIZE));
        long byteSize = (long) oldWords.length * Integer.BYTES;
        if (emitterStart < (long) HEADER_WORDS * Integer.BYTES
                || emitterEnd > byteSize
                || (emitterStart & 3L) != 0L) {
            throw new IllegalArgumentException("Legacy compiled light payload is inconsistent");
        }
        int[] upgraded = oldWords.clone();
        int emitterWords = ShaderAbi.LIGHT_EMITTER_SIZE / Integer.BYTES;
        int uvWord = Math.toIntExact(
                (emitterStart + ShaderAbi.LIGHT_EMITTER_UVS_TINT_OFFSET)
                        / Integer.BYTES);
        for (int emitter = 0; emitter < emitterCount; emitter++) {
            int base = uvWord + emitter * emitterWords;
            for (int vertex = 0; vertex < 3; vertex++) {
                int legacy = upgraded[base + vertex];
                int fixed = PrimitivePacking.upgradeHalfUv(legacy);
                float legacyU = Float.float16ToFloat((short) legacy);
                float legacyV = Float.float16ToFloat((short) (legacy >>> 16));
                if (PrimitivePacking.unpackUv(fixed, false) != legacyU
                        || PrimitivePacking.unpackUv(fixed, true) != legacyV) {
                    throw new IllegalArgumentException(
                            "Legacy emitter UV cannot be upgraded exactly");
                }
                upgraded[base + vertex] = fixed;
            }
        }
        return upgraded;
    }

    /** Rebuilds the v10 tree streams as clustered leaves while preserving emitter and cell data. */
    static int[] upgradeTreeLayout(int[] oldWords, int emitterCount) {
        Objects.requireNonNull(oldWords, "oldWords");
        if (emitterCount <= 0 || oldWords.length < HEADER_WORDS) {
            throw new IllegalArgumentException("Legacy compiled light payload is inconsistent");
        }
        long oldEmitterStart = getLong(oldWords, 6);
        long oldCellStart = getLong(oldWords, 8);
        long oldForwardStart = getLong(oldWords, 2);
        long byteSize = (long) oldWords.length * Integer.BYTES;
        long oldEmitterEnd = Math.addExact(
                oldEmitterStart,
                Math.multiplyExact((long) emitterCount, ShaderAbi.LIGHT_EMITTER_SIZE));
        long oldRootDirectionEnd = Math.addExact(
                oldForwardStart, LEGACY_LIGHT_NODE_FORWARD_SIZE);
        if (oldEmitterStart < (long) HEADER_WORDS * Integer.BYTES
                || oldForwardStart < (long) HEADER_WORDS * Integer.BYTES
                || oldRootDirectionEnd > oldEmitterStart
                || oldEmitterEnd != oldCellStart
                || oldCellStart > byteSize
                || ((oldForwardStart | oldEmitterStart) & 3L) != 0L) {
            throw new IllegalArgumentException("Legacy compiled light payload is inconsistent");
        }

        int emitterWords = ShaderAbi.LIGHT_EMITTER_SIZE / Integer.BYTES;
        int emitterWord = Math.toIntExact(oldEmitterStart / Integer.BYTES);
        CpuLightTree.Leaves leaves = new CpuLightTree.Leaves(emitterCount);
        EmissionDistribution.SpatialMoments moments = EmissionDistribution.uniform().spatialMoments();
        for (int emitter = 0; emitter < emitterCount; emitter++) {
            int base = emitterWord + emitter * emitterWords;
            float cornerX = Float.intBitsToFloat(oldWords[base]);
            float cornerY = Float.intBitsToFloat(oldWords[base + 1]);
            float cornerZ = Float.intBitsToFloat(oldWords[base + 2]);
            float edgeOneX = Float.intBitsToFloat(oldWords[base + 4]);
            float edgeOneY = Float.intBitsToFloat(oldWords[base + 5]);
            float edgeOneZ = Float.intBitsToFloat(oldWords[base + 6]);
            float edgeTwoX = Float.intBitsToFloat(oldWords[base + 8]);
            float edgeTwoY = Float.intBitsToFloat(oldWords[base + 9]);
            float edgeTwoZ = Float.intBitsToFloat(oldWords[base + 10]);
            float secondX = cornerX + edgeOneX;
            float secondY = cornerY + edgeOneY;
            float secondZ = cornerZ + edgeOneZ;
            float thirdX = cornerX + edgeTwoX;
            float thirdY = cornerY + edgeTwoY;
            float thirdZ = cornerZ + edgeTwoZ;
            int flags = oldWords[base + 22];
            leaves.addWithSpatialVariance(
                    Math.min(cornerX, Math.min(secondX, thirdX)),
                    Math.min(cornerY, Math.min(secondY, thirdY)),
                    Math.min(cornerZ, Math.min(secondZ, thirdZ)),
                    Math.max(cornerX, Math.max(secondX, thirdX)),
                    Math.max(cornerY, Math.max(secondY, thirdY)),
                    Math.max(cornerZ, Math.max(secondZ, thirdZ)),
                    cornerX + edgeOneX * moments.meanU() + edgeTwoX * moments.meanV(),
                    cornerY + edgeOneY * moments.meanU() + edgeTwoY * moments.meanV(),
                    cornerZ + edgeOneZ * moments.meanU() + edgeTwoZ * moments.meanV(),
                    moments.positionVariance(
                            edgeOneX,
                            edgeOneY,
                            edgeOneZ,
                            edgeTwoX,
                            edgeTwoY,
                            edgeTwoZ),
                    Float.intBitsToFloat(oldWords[base + 11]),
                    emitter,
                    LightDirection.fromNormal(
                            Float.intBitsToFloat(oldWords[base + 12]),
                            Float.intBitsToFloat(oldWords[base + 13]),
                            Float.intBitsToFloat(oldWords[base + 14]),
                            (flags & CpuSectionLights.EMITTER_FLAG_TWO_SIDED) != 0));
        }
        CpuLightTree.Result tree = CpuLightTree.buildOwned(
                leaves, emitterCount, CpuLightTree.LOCAL_SOFTENING_SCALE);
        int headerWords = ShaderAbi.SECTION_LIGHT_HEADER_SIZE / Integer.BYTES;
        int nodeStart = headerWords;
        int leafStart = nodeStart
                + tree.nodeCount() * (ShaderAbi.LIGHT_NODE_SIZE / Integer.BYTES);
        int entryStart = leafStart
                + tree.clusterCount() * (ShaderAbi.LIGHT_LEAF_SIZE / Integer.BYTES);
        int newEmitterStart = Math.toIntExact(alignUp(
                        (long) (entryStart
                                        + tree.entryCount()
                                                * (ShaderAbi.LIGHT_LEAF_ENTRY_SIZE
                                                        / Integer.BYTES))
                                * Integer.BYTES,
                        16L)
                / Integer.BYTES);
        int cellWords = oldWords.length - Math.toIntExact(oldCellStart / Integer.BYTES);
        int newCellStart = newEmitterStart + emitterCount * emitterWords;
        int[] upgraded = new int[newCellStart + cellWords];
        putLong(upgraded, 0, (long) nodeStart * Integer.BYTES);
        putLong(upgraded, 2, (long) leafStart * Integer.BYTES);
        putLong(upgraded, 4, (long) entryStart * Integer.BYTES);
        putLong(upgraded, 6, (long) newEmitterStart * Integer.BYTES);
        putLong(upgraded, 8, (long) newCellStart * Integer.BYTES);
        upgraded[10] = 0;
        upgraded[11] = emitterCount;
        tree.packInto(upgraded, nodeStart, leafStart, entryStart);
        upgraded[nodeStart + 3] = oldWords[Math.toIntExact(oldForwardStart / Integer.BYTES) + 1];
        System.arraycopy(
                oldWords,
                emitterWord,
                upgraded,
                newEmitterStart,
                emitterCount * emitterWords);
        System.arraycopy(
                oldWords,
                Math.toIntExact(oldCellStart / Integer.BYTES),
                upgraded,
                newCellStart,
                cellWords);
        for (int emitter = 0; emitter < emitterCount; emitter++) {
            upgraded[newEmitterStart + emitter * emitterWords + 21] = tree.leafPath(emitter);
        }
        return upgraded;
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
            int[] words,
            long[] offsets,
            int byteSize,
            int emitterCount,
            int packedDirection) {
        long nodeStart = offsets[0];
        long leafStart = offsets[1];
        long entryStart = offsets[2];
        long emitterStart = offsets[3];
        long cellStart = offsets[4];
        long headerBytes = (long) HEADER_WORDS * Integer.BYTES;
        if (words[10] != 0
                || nodeStart != headerBytes
                || leafStart < nodeStart
                || entryStart < leafStart
                || emitterStart < entryStart
                || cellStart < emitterStart) {
            throw new IllegalArgumentException(
                    "Compiled light payload has an invalid section order");
        }
        long nodeBytes = leafStart - nodeStart;
        if (nodeBytes % ShaderAbi.LIGHT_NODE_SIZE != 0L) {
            throw new IllegalArgumentException(
                    "Compiled light node stream is misaligned");
        }
        long nodeCount = nodeBytes / ShaderAbi.LIGHT_NODE_SIZE;
        long leafBytes = entryStart - leafStart;
        if (leafBytes % ShaderAbi.LIGHT_LEAF_SIZE != 0L) {
            throw new IllegalArgumentException("Compiled light leaf streams are misaligned");
        }
        long leafCount = leafBytes / ShaderAbi.LIGHT_LEAF_SIZE;
        long entryCount = emitterCount;
        long expectedEmitter = alignUp(
                Math.addExact(
                        entryStart,
                        Math.multiplyExact(entryCount, ShaderAbi.LIGHT_LEAF_ENTRY_SIZE)),
                16L);
        long expectedCells = Math.addExact(
                emitterStart,
                Math.multiplyExact(
                        (long) emitterCount, ShaderAbi.LIGHT_EMITTER_SIZE));
        long distributionBytes = Math.multiplyExact(
                (long) EmissionDistribution.CELL_COUNT,
                ShaderAbi.LIGHT_CELL_SIZE);
        long distributionCount = (byteSize - cellStart) / distributionBytes;
        if (emitterStart != expectedEmitter
                || cellStart != expectedCells
                || (byteSize - cellStart) % distributionBytes != 0L
                || nodeCount <= 0L
                || nodeCount > Math.subtractExact(Math.multiplyExact((long) emitterCount, 2L), 1L)
                || leafCount <= 0L
                || leafCount > emitterCount
                || entryCount != emitterCount
                || distributionCount == 0L) {
            throw new IllegalArgumentException(
                    "Compiled light payload disagrees with the shader ABI");
        }
        int rootDirectionWord = Math.toIntExact(
                (nodeStart
                                + ShaderAbi.LIGHT_NODE_PACKED_BOUNDS_DIRECTION_OFFSET
                                + 3L * Integer.BYTES)
                        / Integer.BYTES);
        if (words[rootDirectionWord] != packedDirection) {
            throw new IllegalArgumentException(
                    "Compiled light summary disagrees with its root direction");
        }
        validateTreeAndEmitterReferences(
                words,
                nodeStart,
                leafStart,
                entryStart,
                emitterStart,
                nodeCount,
                leafCount,
                emitterCount,
                distributionCount);
    }

    private static void validateTreeAndEmitterReferences(
            int[] words,
            long nodeStart,
            long leafStart,
            long entryStart,
            long emitterStart,
            long nodeCount,
            long leafCount,
            int emitterCount,
            long distributionCount) {
        int nodeWord = Math.toIntExact(nodeStart / Integer.BYTES);
        int leafWord = Math.toIntExact(leafStart / Integer.BYTES);
        int entryWord = Math.toIntExact(entryStart / Integer.BYTES);
        int nodeWords = ShaderAbi.LIGHT_NODE_SIZE / Integer.BYTES;
        int leafWords = ShaderAbi.LIGHT_LEAF_SIZE / Integer.BYTES;
        int entryWords = ShaderAbi.LIGHT_LEAF_ENTRY_SIZE / Integer.BYTES;
        int childOrLeafWord = ShaderAbi.LIGHT_NODE_CHILD_RESERVED_OFFSET / Integer.BYTES;
        for (int node = 0; node < nodeCount; node++) {
            int childOrLeaf = words[nodeWord + node * nodeWords + childOrLeafWord];
            if ((childOrLeaf & CpuLightTree.LEAF_FLAG) != 0) {
                if ((childOrLeaf & CpuLightTree.INDEX_MASK) >= leafCount) {
                    throw new IllegalArgumentException(
                            "Compiled light tree contains an invalid leaf");
                }
            } else if (childOrLeaf < 0
                    || childOrLeaf + 1L >= nodeCount
                    || (childOrLeaf & 1) == 0) {
                throw new IllegalArgumentException(
                        "Compiled light tree contains invalid children");
            }
        }

        boolean[] seenEmitters = new boolean[emitterCount];
        for (int leaf = 0; leaf < leafCount; leaf++) {
            int base = leafWord + leaf * leafWords;
            long first = Integer.toUnsignedLong(words[base]);
            long count = Integer.toUnsignedLong(words[base + 1]);
            if (count == 0L
                    || count > CpuLightTree.MAX_LIGHTS_PER_LEAF
                    || first + count > emitterCount) {
                throw new IllegalArgumentException("Compiled light tree contains an invalid leaf range");
            }
            for (long offset = 0; offset < count; offset++) {
                int entry = entryWord + Math.toIntExact(first + offset) * entryWords;
                int emitter = words[entry];
                float power = Float.intBitsToFloat(words[entry + 1]);
                if (emitter < 0
                        || emitter >= emitterCount
                        || seenEmitters[emitter]
                        || !(power > 0.0F)
                        || !Float.isFinite(power)) {
                    throw new IllegalArgumentException("Compiled light leaf entry is invalid");
                }
                seenEmitters[emitter] = true;
            }
        }

        int emitterWords = ShaderAbi.LIGHT_EMITTER_SIZE / Integer.BYTES;
        int metadataWord =
                ShaderAbi.LIGHT_EMITTER_METADATA_OFFSET / Integer.BYTES;
        int emitterWord = Math.toIntExact(emitterStart / Integer.BYTES);
        for (int emitter = 0; emitter < emitterCount; emitter++) {
            int metadata = emitterWord + emitter * emitterWords + metadataWord;
            long firstCell = Integer.toUnsignedLong(words[metadata]);
            int path = words[metadata + 1];
            if (firstCell % EmissionDistribution.CELL_COUNT != 0L
                    || firstCell / EmissionDistribution.CELL_COUNT
                            >= distributionCount
                    || !pathContainsEmitter(
                            words,
                            nodeWord,
                            nodeWords,
                            childOrLeafWord,
                            leafWord,
                            leafWords,
                            entryWord,
                            entryWords,
                            path,
                            emitter)) {
                throw new IllegalArgumentException(
                        "Compiled light emitter references invalid tree or distribution data");
            }
        }
    }

    private static boolean pathContainsEmitter(
            int[] words,
            int nodeWord,
            int nodeWords,
            int childOrLeafWord,
            int leafWord,
            int leafWords,
            int entryWord,
            int entryWords,
            int path,
            int expectedEmitter) {
        int depth = path >>> CpuLightTree.MAX_PATH_DEPTH;
        int trailMask = (1 << CpuLightTree.MAX_PATH_DEPTH) - 1;
        int trail = path & trailMask;
        if (depth > CpuLightTree.MAX_PATH_DEPTH
                || (depth < CpuLightTree.MAX_PATH_DEPTH && (trail >>> depth) != 0)) {
            return false;
        }
        int node = 0;
        for (int level = 0; level < depth; level++) {
            int child = words[nodeWord + node * nodeWords + childOrLeafWord];
            if ((child & CpuLightTree.LEAF_FLAG) != 0) {
                return false;
            }
            node = child + ((trail >>> level) & 1);
        }
        int childOrLeaf = words[nodeWord + node * nodeWords + childOrLeafWord];
        if ((childOrLeaf & CpuLightTree.LEAF_FLAG) == 0) {
            return false;
        }
        int leaf = childOrLeaf & CpuLightTree.INDEX_MASK;
        int first = words[leafWord + leaf * leafWords];
        int count = words[leafWord + leaf * leafWords + 1];
        for (int offset = 0; offset < count; offset++) {
            if (words[entryWord + (first + offset) * entryWords] == expectedEmitter) {
                return true;
            }
        }
        return false;
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
            float power,
            int packedDirection) {
        private static final Summary EMPTY =
                new Summary(
                        0,
                        0.0F,
                        0.0F,
                        0.0F,
                        0.0F,
                        0.0F,
                        0.0F,
                        0.0F,
                        LightDirection.FULL);

        public Summary(
                int emitterCount,
                float minX,
                float minY,
                float minZ,
                float maxX,
                float maxY,
                float maxZ,
                float power) {
            this(
                    emitterCount,
                    minX,
                    minY,
                    minZ,
                    maxX,
                    maxY,
                    maxZ,
                    power,
                    LightDirection.FULL);
        }

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
                if (power != 0.0F || packedDirection != LightDirection.FULL) {
                    throw new IllegalArgumentException(
                            "Empty compiled lights must have zero power and full directional support");
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
