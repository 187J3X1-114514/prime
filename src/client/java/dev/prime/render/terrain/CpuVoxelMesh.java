package dev.prime.render.terrain;

import java.util.Arrays;
import java.util.Objects;

/**
 * One reusable texture-derived height-field BLAS.
 *
 * <p>All instances of this value share the same immutable position and primitive storage. Per-face
 * translation and tint remain in {@link CpuVoxelInstances}, so biome colors do not duplicate a
 * high-detail mesh.
 */
public final class CpuVoxelMesh {
    private final float[] positions;
    private final int[] primitiveRecords;
    private final int opaqueTriangleCount;
    private final int cutoutTriangleCount;
    private final int transmissiveTriangleCount;
    private final OpacityMicromapData opacityMicromap;
    private final int gpuContentHash;

    public CpuVoxelMesh(
            float[] positions,
            int[] primitiveRecords,
            int opaqueTriangleCount,
            int cutoutTriangleCount,
            int transmissiveTriangleCount,
            OpacityMicromapData opacityMicromap) {
        this.positions = Objects.requireNonNull(positions, "positions");
        this.primitiveRecords = Objects.requireNonNull(
                primitiveRecords, "primitiveRecords");
        this.opacityMicromap = Objects.requireNonNull(
                opacityMicromap, "opacityMicromap");
        int triangles = Math.addExact(
                Math.addExact(opaqueTriangleCount, cutoutTriangleCount),
                transmissiveTriangleCount);
        if (opaqueTriangleCount < 0
                || cutoutTriangleCount < 0
                || transmissiveTriangleCount < 0
                || positions.length != Math.multiplyExact(triangles, 9)
                || primitiveRecords.length
                        != Math.multiplyExact(triangles, CpuSectionMesh.PRIMITIVE_WORDS)
                || opacityMicromap.triangleCount() != cutoutTriangleCount) {
            throw new IllegalArgumentException("Invalid reusable voxel-surface mesh");
        }
        if (triangles == 0) {
            throw new IllegalArgumentException(
                    "A reusable voxel-surface mesh must contain geometry");
        }
        this.opaqueTriangleCount = opaqueTriangleCount;
        this.cutoutTriangleCount = cutoutTriangleCount;
        this.transmissiveTriangleCount = transmissiveTriangleCount;
        int hash = rawFloatHash(this.positions);
        hash = 31 * hash + Arrays.hashCode(this.primitiveRecords);
        hash = 31 * hash + this.opaqueTriangleCount;
        hash = 31 * hash + this.cutoutTriangleCount;
        hash = 31 * hash + this.transmissiveTriangleCount;
        hash = 31 * hash + Arrays.hashCode(this.opacityMicromap.blocks());
        hash = 31 * hash + Arrays.hashCode(this.opacityMicromap.blockOffsets());
        hash = 31 * hash + Arrays.hashCode(this.opacityMicromap.blockFormats());
        hash = 31 * hash + Arrays.hashCode(
                this.opacityMicromap.blockSubdivisionLevels());
        hash = 31 * hash + Arrays.hashCode(this.opacityMicromap.triangleIndices());
        this.gpuContentHash = hash;
    }

    /** Borrowed read-only backing storage. */
    public float[] positions() {
        return this.positions;
    }

    /** Borrowed read-only backing storage. */
    public int[] primitiveRecords() {
        return this.primitiveRecords;
    }

    public int opaqueTriangleCount() {
        return this.opaqueTriangleCount;
    }

    public int cutoutTriangleCount() {
        return this.cutoutTriangleCount;
    }

    public int transmissiveTriangleCount() {
        return this.transmissiveTriangleCount;
    }

    public int triangleCount() {
        return Math.addExact(
                Math.addExact(this.opaqueTriangleCount, this.cutoutTriangleCount),
                this.transmissiveTriangleCount);
    }

    public OpacityMicromapData opacityMicromap() {
        return this.opacityMicromap;
    }

    public long positionBytes() {
        return Math.multiplyExact((long) this.positions.length, Float.BYTES);
    }

    public long primitiveBytes() {
        return Math.multiplyExact((long) this.primitiveRecords.length, Integer.BYTES);
    }

    public long byteSize() {
        return Math.addExact(
                Math.addExact(this.positionBytes(), this.primitiveBytes()),
                this.opacityMicromap.byteSize());
    }

    /** Stable fingerprint of the borrowed read-only GPU payload. */
    public int gpuContentHash() {
        return this.gpuContentHash;
    }

    private static int rawFloatHash(float[] values) {
        int result = 1;
        for (float value : values) {
            result = 31 * result + Float.floatToRawIntBits(value);
        }
        return result;
    }
}
