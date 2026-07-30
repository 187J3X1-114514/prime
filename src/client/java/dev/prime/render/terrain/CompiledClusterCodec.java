package dev.prime.render.terrain;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Objects;

/** Versioned canonical binary encoding of one {@link CompiledCluster}. */
public final class CompiledClusterCodec {
    private static final int MAGIC = 0x3143_4350;
    private static final int VERSION = 3;
    private static final int MAX_SEGMENTS = 4_096;
    private static final int MAX_VOXEL_MESHES = 4_096;
    private static final int MAX_VOXEL_INSTANCES = 4_194_304;
    private static final int MAX_ENCODED_BYTES = 1 << 30;

    private CompiledClusterCodec() {
    }

    public static byte[] encode(CompiledCluster cluster) {
        Objects.requireNonNull(cluster, "cluster");
        CpuClusterMesh mesh = cluster.mesh();
        validatePrimitiveRecords(mesh);
        OpacityMicromapData opacity = mesh.opacityMicromap();
        opacity.requireValidTriangleIndices();
        int byteSize = Math.toIntExact(encodedByteSize(cluster));
        ByteBuffer output = ByteBuffer.allocate(byteSize).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(MAGIC);
        output.putInt(VERSION);
        output.putLong(cluster.key());
        output.putInt(cluster.clusterX());
        output.putInt(cluster.clusterY());
        output.putInt(cluster.clusterZ());

        output.putLong(mesh.opaqueTriangleCount());
        output.putLong(mesh.cutoutTriangleCount());
        output.putLong(mesh.transmissiveTriangleCount());
        output.putInt(mesh.segments().size());
        for (CpuClusterMesh.Segment segment : mesh.segments()) {
            output.putInt(segment.opaqueTriangleCount());
            output.putInt(segment.cutoutTriangleCount());
            output.putInt(segment.transmissiveTriangleCount());
            putFloats(output, segment.positions());
            putInts(output, segment.primitiveRecords());
        }

        putBytes(output, opacity.blocks());
        putInts(output, opacity.blockOffsets());
        putInts(output, opacity.blockFormats());
        putInts(output, opacity.blockSubdivisionLevels());
        putInts(output, opacity.triangleIndices());

        output.putInt(mesh.voxelMeshes().size());
        for (CpuVoxelMesh voxelMesh : mesh.voxelMeshes()) {
            output.putInt(voxelMesh.opaqueTriangleCount());
            output.putInt(voxelMesh.cutoutTriangleCount());
            output.putInt(voxelMesh.transmissiveTriangleCount());
            putFloats(output, voxelMesh.positions());
            putInts(output, voxelMesh.primitiveRecords());
            putOpacity(output, voxelMesh.opacityMicromap());
        }
        putInts(output, mesh.voxelInstances().meshIndices());
        putInts(output, mesh.voxelInstances().packedTints());
        putFloats(output, mesh.voxelInstances().translations());

        CompiledClusterLights.Summary lights = mesh.lights().summary();
        output.putInt(lights.emitterCount());
        output.putFloat(lights.minX());
        output.putFloat(lights.minY());
        output.putFloat(lights.minZ());
        output.putFloat(lights.maxX());
        output.putFloat(lights.maxY());
        output.putFloat(lights.maxZ());
        output.putFloat(lights.power());
        putInts(output, mesh.lights().encodedWords());
        if (output.hasRemaining()) {
            throw new AssertionError("Compiled-cluster size calculation is incomplete");
        }
        return output.array();
    }

    public static CompiledCluster decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("Compiled-cluster replay exceeds the size limit");
        }
        ByteBuffer input = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        try {
            if (input.getInt() != MAGIC) {
                throw new IllegalArgumentException(
                        "Unsupported compiled-cluster replay header");
            }
            int version = input.getInt();
            if (version < 1 || version > VERSION) {
                throw new IllegalArgumentException(
                        "Unsupported compiled-cluster replay header");
            }
            long key = input.getLong();
            int clusterX = input.getInt();
            int clusterY = input.getInt();
            int clusterZ = input.getInt();
            long opaque = nonnegative(input.getLong(), "opaque triangle count");
            long cutout = nonnegative(input.getLong(), "cutout triangle count");
            long transmissive = nonnegative(
                    input.getLong(), "transmissive triangle count");
            int segmentCount = boundedCount(
                    input.getInt(), MAX_SEGMENTS, "cluster segment count");
            ArrayList<CpuClusterMesh.Segment> segments =
                    new ArrayList<>(segmentCount);
            for (int index = 0; index < segmentCount; index++) {
                int segmentOpaque = nonnegative(
                        input.getInt(), "segment opaque triangle count");
                int segmentCutout = nonnegative(
                        input.getInt(), "segment cutout triangle count");
                int segmentTransmissive = nonnegative(
                        input.getInt(), "segment transmissive triangle count");
                int triangles = Math.addExact(
                        Math.addExact(segmentOpaque, segmentCutout),
                        segmentTransmissive);
                float[] positions = getFloats(
                        input,
                        Math.multiplyExact(triangles, 9),
                        "segment positions");
                int[] primitives = getInts(
                        input,
                        Math.multiplyExact(triangles, CpuSectionMesh.PRIMITIVE_WORDS),
                        "segment primitive records");
                segments.add(new CpuClusterMesh.Segment(
                        positions,
                        primitives,
                        segmentOpaque,
                        segmentCutout,
                        segmentTransmissive));
            }

            byte[] opacityBlocks = getBytes(input, "opacity blocks");
            int[] opacityOffsets = getInts(input, "opacity block offsets");
            int[] opacityFormats = getInts(input, "opacity block formats");
            int[] opacitySubdivisions = getInts(
                    input, "opacity subdivision levels");
            int[] opacityTriangles = getInts(
                    input, "opacity triangle indices");
            OpacityMicromapData opacity = OpacityMicromapData.fromEncoded(
                    opacityBlocks,
                    opacityOffsets,
                    opacityFormats,
                    opacitySubdivisions,
                    opacityTriangles);

            ArrayList<CpuVoxelMesh> voxelMeshes = new ArrayList<>();
            CpuVoxelInstances voxelInstances = CpuVoxelInstances.EMPTY;
            if (version >= 2) {
                int voxelMeshCount = boundedCount(
                        input.getInt(), MAX_VOXEL_MESHES, "voxel mesh count");
                voxelMeshes.ensureCapacity(voxelMeshCount);
                for (int index = 0; index < voxelMeshCount; index++) {
                    int meshOpaque = nonnegative(
                            input.getInt(), "voxel mesh opaque triangle count");
                    int meshCutout = nonnegative(
                            input.getInt(), "voxel mesh cutout triangle count");
                    int meshTransmissive = nonnegative(
                            input.getInt(), "voxel mesh transmissive triangle count");
                    int triangles = Math.addExact(
                            Math.addExact(meshOpaque, meshCutout),
                            meshTransmissive);
                    float[] meshPositions = getFloats(
                            input,
                            Math.multiplyExact(triangles, 9),
                            "voxel mesh positions");
                    int[] meshPrimitives = getInts(
                            input,
                            Math.multiplyExact(
                                    triangles, CpuSectionMesh.PRIMITIVE_WORDS),
                            "voxel mesh primitive records");
                    voxelMeshes.add(new CpuVoxelMesh(
                            meshPositions,
                            meshPrimitives,
                            meshOpaque,
                            meshCutout,
                            meshTransmissive,
                            getOpacity(input)));
                }
                int[] meshIndices = getInts(input, "voxel instance mesh indices");
                if (meshIndices.length > MAX_VOXEL_INSTANCES) {
                    throw new IllegalArgumentException(
                            "Compiled-cluster voxel instance count is invalid");
                }
                int[] packedTints = getInts(input, "voxel instance tints");
                float[] translations = getFloats(
                        input,
                        Math.multiplyExact(meshIndices.length, 3),
                        "voxel instance translations");
                voxelInstances = new CpuVoxelInstances(
                        meshIndices, packedTints, translations);
            }

            CompiledClusterLights.Summary lightSummary =
                    new CompiledClusterLights.Summary(
                            nonnegative(input.getInt(), "light emitter count"),
                            input.getFloat(),
                            input.getFloat(),
                            input.getFloat(),
                            input.getFloat(),
                            input.getFloat(),
                            input.getFloat(),
                            input.getFloat());
            CompiledClusterLights lights = CompiledClusterLights.fromEncoded(
                    getInts(input, "compiled light words"), lightSummary);
            if (input.hasRemaining()) {
                throw new IllegalArgumentException(
                        "Compiled-cluster replay contains trailing data");
            }
            CpuClusterMesh mesh = CpuClusterMesh.fromEncoded(
                    segments,
                    opaque,
                    cutout,
                    transmissive,
                    opacity,
                    lights,
                    voxelMeshes,
                    voxelInstances);
            validatePrimitiveRecords(mesh);
            return new CompiledCluster(
                    key, clusterX, clusterY, clusterZ, mesh);
        } catch (BufferUnderflowException | ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Compiled-cluster replay is truncated or inconsistent", exception);
        }
    }

    private static long encodedByteSize(CompiledCluster cluster) {
        long result = 56L;
        for (CpuClusterMesh.Segment segment : cluster.mesh().segments()) {
            result = Math.addExact(result, 12L);
            result = arrayBytes(result, segment.positions().length, Float.BYTES);
            result = arrayBytes(
                    result, segment.primitiveRecords().length, Integer.BYTES);
        }
        OpacityMicromapData opacity = cluster.mesh().opacityMicromap();
        result = arrayBytes(result, opacity.blocks().length, Byte.BYTES);
        result = arrayBytes(result, opacity.blockOffsets().length, Integer.BYTES);
        result = arrayBytes(result, opacity.blockFormats().length, Integer.BYTES);
        result = arrayBytes(
                result, opacity.blockSubdivisionLevels().length, Integer.BYTES);
        result = arrayBytes(
                result, opacity.triangleIndices().length, Integer.BYTES);
        result = Math.addExact(result, Integer.BYTES);
        for (CpuVoxelMesh voxelMesh : cluster.mesh().voxelMeshes()) {
            result = Math.addExact(result, 3L * Integer.BYTES);
            result = arrayBytes(
                    result, voxelMesh.positions().length, Float.BYTES);
            result = arrayBytes(
                    result,
                    voxelMesh.primitiveRecords().length,
                    Integer.BYTES);
            result = opacityEncodedByteSize(
                    result, voxelMesh.opacityMicromap());
        }
        CpuVoxelInstances instances = cluster.mesh().voxelInstances();
        result = arrayBytes(result, instances.meshIndices().length, Integer.BYTES);
        result = arrayBytes(result, instances.packedTints().length, Integer.BYTES);
        result = arrayBytes(result, instances.translations().length, Float.BYTES);
        result = Math.addExact(result, 32L);
        result = arrayBytes(
                result,
                cluster.mesh().lights().encodedWords().length,
                Integer.BYTES);
        if (result > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException(
                    "Compiled cluster is too large for the replay format");
        }
        return result;
    }

    private static long opacityEncodedByteSize(
            long current, OpacityMicromapData opacity) {
        long result = arrayBytes(current, opacity.blocks().length, Byte.BYTES);
        result = arrayBytes(result, opacity.blockOffsets().length, Integer.BYTES);
        result = arrayBytes(result, opacity.blockFormats().length, Integer.BYTES);
        result = arrayBytes(
                result, opacity.blockSubdivisionLevels().length, Integer.BYTES);
        return arrayBytes(
                result, opacity.triangleIndices().length, Integer.BYTES);
    }

    private static long arrayBytes(long current, int length, int elementBytes) {
        return Math.addExact(
                Math.addExact(current, Integer.BYTES),
                Math.multiplyExact((long) length, elementBytes));
    }

    private static void putBytes(ByteBuffer output, byte[] values) {
        output.putInt(values.length);
        output.put(values);
    }

    private static void putFloats(ByteBuffer output, float[] values) {
        output.putInt(values.length);
        for (float value : values) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException(
                        "Compiled-cluster positions must be finite");
            }
            output.putInt(Float.floatToRawIntBits(value));
        }
    }

    private static void putOpacity(
            ByteBuffer output, OpacityMicromapData opacity) {
        opacity.requireValidTriangleIndices();
        putBytes(output, opacity.blocks());
        putInts(output, opacity.blockOffsets());
        putInts(output, opacity.blockFormats());
        putInts(output, opacity.blockSubdivisionLevels());
        putInts(output, opacity.triangleIndices());
    }

    private static void putInts(ByteBuffer output, int[] values) {
        output.putInt(values.length);
        for (int value : values) {
            output.putInt(value);
        }
    }

    private static byte[] getBytes(ByteBuffer input, String label) {
        int count = readableCount(input, Byte.BYTES, label);
        byte[] result = new byte[count];
        input.get(result);
        return result;
    }

    private static float[] getFloats(
            ByteBuffer input, int expectedCount, String label) {
        requireExpectedCount(input, expectedCount, Float.BYTES, label);
        float[] result = new float[expectedCount];
        for (int index = 0; index < expectedCount; index++) {
            float value = Float.intBitsToFloat(input.getInt());
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException(
                        "Compiled-cluster " + label + " must be finite");
            }
            result[index] = value;
        }
        return result;
    }

    private static OpacityMicromapData getOpacity(ByteBuffer input) {
        return OpacityMicromapData.fromEncoded(
                getBytes(input, "voxel opacity blocks"),
                getInts(input, "voxel opacity block offsets"),
                getInts(input, "voxel opacity block formats"),
                getInts(input, "voxel opacity subdivision levels"),
                getInts(input, "voxel opacity triangle indices"));
    }

    private static void validatePrimitiveRecords(CpuClusterMesh mesh) {
        int emitterCount = mesh.lights().emitterCount();
        for (CpuClusterMesh.Segment segment : mesh.segments()) {
            validatePrimitiveRecords(
                    segment.primitiveRecords(),
                    segment.opaqueTriangleCount(),
                    segment.cutoutTriangleCount(),
                    segment.transmissiveTriangleCount(),
                    emitterCount);
        }
        for (CpuVoxelMesh voxelMesh : mesh.voxelMeshes()) {
            validatePrimitiveRecords(
                    voxelMesh.primitiveRecords(),
                    voxelMesh.opaqueTriangleCount(),
                    voxelMesh.cutoutTriangleCount(),
                    voxelMesh.transmissiveTriangleCount(),
                    0);
        }
    }

    private static void validatePrimitiveRecords(
            int[] records,
            int opaqueCount,
            int cutoutCount,
            int transmissiveCount,
            int emitterCount) {
        int opaqueEnd = opaqueCount;
        int cutoutEnd = Math.addExact(opaqueEnd, cutoutCount);
        int triangleCount = Math.addExact(cutoutEnd, transmissiveCount);
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            int record = Math.multiplyExact(
                    triangle, CpuSectionMesh.PRIMITIVE_WORDS);
            boolean constantUv =
                    records[record + 6] == PrimitivePacking.CONSTANT_UV_DENSITY;
            int constantMode = 0;
            if (constantUv) {
                constantMode = records[record + 2];
                if ((constantMode & ~PrimitivePacking.CONSTANT_UV_MODE_MASK) != 0
                        || ((constantMode & PrimitivePacking.CONSTANT_UV_OWN_TINT) != 0
                                && (constantMode
                                                & PrimitivePacking.CONSTANT_UV_BAKED_MATERIAL)
                                        == 0)) {
                    throw new IllegalArgumentException(
                            "Compiled-cluster constant UV has invalid reserved data");
                }
                if ((constantMode & PrimitivePacking.CONSTANT_UV_BAKED_MATERIAL) == 0) {
                    requireNormalizedFloatUv(records[record]);
                    requireNormalizedFloatUv(records[record + 1]);
                }
            } else {
                for (int vertex = 0; vertex < 3; vertex++) {
                    requireFiniteHalf2(records[record + vertex]);
                }
            }
            int flags = PrimitivePacking.unpackFlags(
                    records[record + 3], records[record + 5]);
            PrimitivePacking.requireValidFlags(flags);
            boolean cutout = (flags & PrimitivePacking.FLAG_CUTOUT) != 0;
            boolean transmissive =
                    (flags & PrimitivePacking.FLAG_TRANSMISSIVE) != 0;
            if ((constantMode & PrimitivePacking.CONSTANT_UV_BAKED_MATERIAL) != 0
                    && (cutout || transmissive)) {
                throw new IllegalArgumentException(
                        "Compiled-cluster baked material must be opaque");
            }
            boolean categoryMismatch = triangle < opaqueEnd
                    ? cutout || transmissive
                    : triangle < cutoutEnd
                            ? !cutout || transmissive
                            : !transmissive;
            if (categoryMismatch) {
                throw new IllegalArgumentException(
                        "Compiled-cluster primitive flags disagree with geometry categories");
            }
            int emitterIndex = PrimitivePacking.unpackEmitterIndex(
                    records[record + 5]);
            if (emitterIndex >= emitterCount) {
                throw new IllegalArgumentException(
                        "Compiled-cluster primitive references an invalid emitter");
            }
            float uvDensity = Float.intBitsToFloat(records[record + 6]);
            if (!(uvDensity >= 0.0F) || !Float.isFinite(uvDensity)) {
                throw new IllegalArgumentException(
                        "Compiled-cluster UV density must be finite and nonnegative");
            }
        }
    }

    private static void requireFiniteHalf2(int packed) {
        float low = Float.float16ToFloat((short) packed);
        float high = Float.float16ToFloat((short) (packed >>> 16));
        if (!Float.isFinite(low) || !Float.isFinite(high)) {
            throw new IllegalArgumentException(
                    "Compiled-cluster texture coordinates must be finite");
        }
    }

    private static void requireNormalizedFloatUv(int packed) {
        float coordinate = Float.intBitsToFloat(packed);
        if (!(coordinate >= 0.0F && coordinate <= 1.0F)
                || !Float.isFinite(coordinate)) {
            throw new IllegalArgumentException(
                    "Compiled-cluster constant UV must be finite and normalized");
        }
    }

    private static int[] getInts(
            ByteBuffer input, int expectedCount, String label) {
        requireExpectedCount(input, expectedCount, Integer.BYTES, label);
        int[] result = new int[expectedCount];
        for (int index = 0; index < expectedCount; index++) {
            result[index] = input.getInt();
        }
        return result;
    }

    private static int[] getInts(ByteBuffer input, String label) {
        int count = readableCount(input, Integer.BYTES, label);
        int[] result = new int[count];
        for (int index = 0; index < count; index++) {
            result[index] = input.getInt();
        }
        return result;
    }

    private static void requireExpectedCount(
            ByteBuffer input,
            int expectedCount,
            int elementBytes,
            String label) {
        int encodedCount = input.getInt();
        if (encodedCount != expectedCount
                || (long) expectedCount * elementBytes > input.remaining()) {
            throw new IllegalArgumentException(
                    "Compiled-cluster " + label + " length is inconsistent");
        }
    }

    private static int readableCount(
            ByteBuffer input, int elementBytes, String label) {
        int count = input.getInt();
        if (count < 0 || (long) count * elementBytes > input.remaining()) {
            throw new IllegalArgumentException(
                    "Compiled-cluster " + label + " length is invalid");
        }
        return count;
    }

    private static int boundedCount(int value, int maximum, String label) {
        if (value < 0 || value > maximum) {
            throw new IllegalArgumentException(
                    "Compiled-cluster " + label + " is invalid");
        }
        return value;
    }

    private static int nonnegative(int value, String label) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    "Compiled-cluster " + label + " is negative");
        }
        return value;
    }

    private static long nonnegative(long value, String label) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                    "Compiled-cluster " + label + " is negative");
        }
        return value;
    }
}
