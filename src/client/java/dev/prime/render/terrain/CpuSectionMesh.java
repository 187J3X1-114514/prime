package dev.prime.render.terrain;

import java.util.Objects;

/**
 * Logically immutable section mesh.
 *
 * <p>Construction transfers exclusive ownership of both primitive arrays to this value. Accessors
 * expose borrowed read-only storage for zero-copy cluster assembly and upload; callers must never
 * mutate it. Java has no zero-copy read-only primitive-array view, and cloning here would duplicate
 * the dominant terrain payload.
 */
public record CpuSectionMesh(
        float[] positions,
        int[] primitiveRecords,
        int opaqueTriangleCount,
        int cutoutTriangleCount,
        int transmissiveTriangleCount,
        OpacityMicromapData opacityMicromap,
        CpuSectionLights lights) {

    public static final int PRIMITIVE_WORDS = 8;

    public CpuSectionMesh {
        positions = Objects.requireNonNull(positions, "positions");
        primitiveRecords = Objects.requireNonNull(
                primitiveRecords, "primitiveRecords");
        opacityMicromap = Objects.requireNonNull(
                opacityMicromap, "opacityMicromap");
        lights = Objects.requireNonNull(lights, "lights");
        if (opaqueTriangleCount < 0 || cutoutTriangleCount < 0 || transmissiveTriangleCount < 0) {
            throw new IllegalArgumentException("Triangle counts must not be negative");
        }
        int triangleCount = Math.addExact(
                Math.addExact(opaqueTriangleCount, cutoutTriangleCount),
                transmissiveTriangleCount);
        if (positions.length != Math.multiplyExact(triangleCount, 9)) {
            throw new IllegalArgumentException("Position array does not match triangle count");
        }
        if (primitiveRecords.length != Math.multiplyExact(triangleCount, PRIMITIVE_WORDS)) {
            throw new IllegalArgumentException("Primitive array does not match triangle count");
        }
        if (opacityMicromap.triangleCount() != cutoutTriangleCount) {
            throw new IllegalArgumentException("Opacity micromap does not match cutout geometry");
        }
    }

    /** Borrowed read-only backing storage; ownership remains with this mesh. */
    @Override
    public float[] positions() {
        return this.positions;
    }

    /** Borrowed read-only backing storage; ownership remains with this mesh. */
    @Override
    public int[] primitiveRecords() {
        return this.primitiveRecords;
    }

    public boolean isEmpty() {
        return this.triangleCount() == 0;
    }

    public int triangleCount() {
        return Math.addExact(
                Math.addExact(this.opaqueTriangleCount, this.cutoutTriangleCount),
                this.transmissiveTriangleCount);
    }

    public long byteSize() {
        return (long) this.positions.length * Float.BYTES
                + (long) this.primitiveRecords.length * Integer.BYTES
                + this.opacityMicromap.byteSize()
                + this.lights.byteSize();
    }
}
