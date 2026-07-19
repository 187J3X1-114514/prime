package dev.prime.render.terrain;

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
        if (opaqueTriangleCount < 0 || cutoutTriangleCount < 0 || transmissiveTriangleCount < 0) {
            throw new IllegalArgumentException("Triangle counts must not be negative");
        }
        int triangleCount = Math.addExact(
                Math.addExact(opaqueTriangleCount, cutoutTriangleCount),
                transmissiveTriangleCount);
        if (positions.length != triangleCount * 9) {
            throw new IllegalArgumentException("Position array does not match triangle count");
        }
        if (primitiveRecords.length != triangleCount * PRIMITIVE_WORDS) {
            throw new IllegalArgumentException("Primitive array does not match triangle count");
        }
        if (opacityMicromap == null || lights == null) {
            throw new IllegalArgumentException("Section sidecar data must not be null");
        }
        if (opacityMicromap.triangleIndices().length != cutoutTriangleCount) {
            throw new IllegalArgumentException("Opacity micromap does not match cutout geometry");
        }
    }

    public boolean isEmpty() {
        return this.opaqueTriangleCount + this.cutoutTriangleCount + this.transmissiveTriangleCount == 0;
    }

    public long byteSize() {
        return (long) this.positions.length * Float.BYTES
                + (long) this.primitiveRecords.length * Integer.BYTES
                + this.opacityMicromap.byteSize()
                + this.lights.byteSize();
    }
}
