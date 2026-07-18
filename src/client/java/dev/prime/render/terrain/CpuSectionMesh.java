package dev.prime.render.terrain;

public record CpuSectionMesh(
        float[] positions,
        int[] primitiveRecords,
        int opaqueTriangleCount,
        // This is the second BLAS geometry. It contains alpha-tested cutouts and physically
        // transmissive surfaces because both require an any-hit shader; the historical name is
        // retained to avoid changing the generated Section ABI.
        int cutoutTriangleCount,
        CpuSectionLights lights) {

    public CpuSectionMesh {
        int triangleCount = opaqueTriangleCount + cutoutTriangleCount;
        if (positions.length != triangleCount * 9) {
            throw new IllegalArgumentException("Position array does not match triangle count");
        }
        if (primitiveRecords.length != triangleCount * 9) {
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
