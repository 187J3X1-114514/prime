package dev.prime.render.terrain;

public record CpuSectionMesh(
        float[] positions,
        int[] primitiveRecords,
        int opaqueTriangleCount,
        int cutoutTriangleCount,
        CpuSectionLights lights) {

    public CpuSectionMesh {
        int triangleCount = opaqueTriangleCount + cutoutTriangleCount;
        if (positions.length != triangleCount * 9) {
            throw new IllegalArgumentException("Position array does not match triangle count");
        }
        if (primitiveRecords.length != triangleCount * 8) {
            throw new IllegalArgumentException("Primitive array does not match triangle count");
        }
        if (lights == null) {
            throw new IllegalArgumentException("Section light data must not be null");
        }
    }

    public boolean isEmpty() {
        return this.opaqueTriangleCount + this.cutoutTriangleCount == 0;
    }

    public long byteSize() {
        return (long) this.positions.length * Float.BYTES
                + (long) this.primitiveRecords.length * Integer.BYTES
                + this.lights.byteSize();
    }
}
