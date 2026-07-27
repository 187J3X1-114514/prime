package dev.prime.render.terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One logically immutable BLAS payload backed by bounded CPU segments.
 *
 * <p>Segment arrays are ownership-transferred and exposed only as borrowed read-only storage. The
 * representation deliberately avoids joining them into another full-size CPU mesh.
 */
public final class CpuClusterMesh {
    private final List<Segment> segments;
    private final long opaqueTriangleCount;
    private final long cutoutTriangleCount;
    private final long transmissiveTriangleCount;
    private final OpacityMicromapData opacityMicromap;
    private final CompiledClusterLights lights;

    private CpuClusterMesh(
            List<Segment> segments,
            long opaqueTriangleCount,
            long cutoutTriangleCount,
            long transmissiveTriangleCount,
            OpacityMicromapData opacityMicromap,
            CompiledClusterLights lights) {
        this.segments = List.copyOf(segments);
        if (opaqueTriangleCount < 0L
                || cutoutTriangleCount < 0L
                || transmissiveTriangleCount < 0L) {
            throw new IllegalArgumentException("Cluster triangle counts must not be negative");
        }
        long segmentOpaque = 0L;
        long segmentCutout = 0L;
        long segmentTransmissive = 0L;
        for (Segment segment : this.segments) {
            segmentOpaque = Math.addExact(
                    segmentOpaque, segment.opaqueTriangleCount());
            segmentCutout = Math.addExact(
                    segmentCutout, segment.cutoutTriangleCount());
            segmentTransmissive = Math.addExact(
                    segmentTransmissive, segment.transmissiveTriangleCount());
        }
        if (segmentOpaque != opaqueTriangleCount
                || segmentCutout != cutoutTriangleCount
                || segmentTransmissive != transmissiveTriangleCount) {
            throw new IllegalArgumentException(
                    "Cluster segments disagree with aggregate triangle counts");
        }
        Objects.requireNonNull(opacityMicromap, "opacityMicromap");
        Objects.requireNonNull(lights, "lights");
        if (opacityMicromap.triangleCount() != cutoutTriangleCount) {
            throw new IllegalArgumentException(
                    "Cluster opacity micromap does not match cutout geometry");
        }
        this.opaqueTriangleCount = opaqueTriangleCount;
        this.cutoutTriangleCount = cutoutTriangleCount;
        this.transmissiveTriangleCount = transmissiveTriangleCount;
        this.opacityMicromap = opacityMicromap;
        this.lights = lights;
    }

    static CpuClusterMesh fromEncoded(
            List<Segment> segments,
            long opaqueTriangleCount,
            long cutoutTriangleCount,
            long transmissiveTriangleCount,
            OpacityMicromapData opacityMicromap,
            CompiledClusterLights lights) {
        return new CpuClusterMesh(
                segments,
                opaqueTriangleCount,
                cutoutTriangleCount,
                transmissiveTriangleCount,
                opacityMicromap,
                lights);
    }

    static CpuClusterMesh fromSegments(List<CpuSectionMesh> meshes) {
        ArrayList<Segment> segments = new ArrayList<>(meshes.size());
        ArrayList<CpuSectionLights.Translated> lightSources = new ArrayList<>();
        OpacityMicromapData.Builder opacityMicromap = new OpacityMicromapData.Builder();
        long opaque = 0L;
        long cutout = 0L;
        long transmissive = 0L;
        for (CpuSectionMesh mesh : meshes) {
            if (mesh.isEmpty()) {
                continue;
            }
            segments.add(new Segment(
                    mesh.positions(),
                    mesh.primitiveRecords(),
                    mesh.opaqueTriangleCount(),
                    mesh.cutoutTriangleCount(),
                    mesh.transmissiveTriangleCount()));
            opaque = Math.addExact(opaque, mesh.opaqueTriangleCount());
            cutout = Math.addExact(cutout, mesh.cutoutTriangleCount());
            transmissive = Math.addExact(transmissive, mesh.transmissiveTriangleCount());
            opacityMicromap.append(mesh.opacityMicromap());
            if (!mesh.lights().isEmpty()) {
                lightSources.add(new CpuSectionLights.Translated(
                        mesh.lights(), 0.0F, 0.0F, 0.0F));
            }
        }
        return new CpuClusterMesh(
                segments,
                opaque,
                cutout,
                transmissive,
                opacityMicromap.build(),
                CompiledClusterLights.compile(CpuSectionLights.merge(lightSources)));
    }

    static CpuClusterMesh empty() {
        return new CpuClusterMesh(
                List.of(),
                0L,
                0L,
                0L,
                OpacityMicromapData.EMPTY,
                CompiledClusterLights.EMPTY);
    }

    public List<Segment> segments() {
        return this.segments;
    }

    public long opaqueTriangleCount() {
        return this.opaqueTriangleCount;
    }

    public long cutoutTriangleCount() {
        return this.cutoutTriangleCount;
    }

    public long transmissiveTriangleCount() {
        return this.transmissiveTriangleCount;
    }

    public long triangleCount() {
        return Math.addExact(
                Math.addExact(this.opaqueTriangleCount, this.cutoutTriangleCount),
                this.transmissiveTriangleCount);
    }

    public OpacityMicromapData opacityMicromap() {
        return this.opacityMicromap;
    }

    public CompiledClusterLights lights() {
        return this.lights;
    }

    public boolean isEmpty() {
        return this.segments.isEmpty();
    }

    public long positionBytes() {
        return Math.multiplyExact(
                this.triangleCount(), 9L * Float.BYTES);
    }

    public long primitiveBytes() {
        return Math.multiplyExact(
                this.triangleCount(), (long) CpuSectionMesh.PRIMITIVE_WORDS * Integer.BYTES);
    }

    public long byteSize() {
        return Math.addExact(
                Math.addExact(this.positionBytes(), this.primitiveBytes()),
                Math.addExact(this.opacityMicromap.byteSize(), this.lights.byteSize()));
    }

    /**
     * An ownership-transferred CPU storage segment; segmentation does not create another BLAS or
     * TLAS instance.
     */
    public record Segment(
            float[] positions,
            int[] primitiveRecords,
            int opaqueTriangleCount,
            int cutoutTriangleCount,
            int transmissiveTriangleCount) {
        public Segment {
            positions = Objects.requireNonNull(positions, "positions");
            primitiveRecords = Objects.requireNonNull(
                    primitiveRecords, "primitiveRecords");
            int triangles = Math.addExact(
                    Math.addExact(opaqueTriangleCount, cutoutTriangleCount),
                    transmissiveTriangleCount);
            if (opaqueTriangleCount < 0
                    || cutoutTriangleCount < 0
                    || transmissiveTriangleCount < 0
                    || positions.length != Math.multiplyExact(triangles, 9)
                    || primitiveRecords.length
                            != Math.multiplyExact(triangles, CpuSectionMesh.PRIMITIVE_WORDS)) {
                throw new IllegalArgumentException("Invalid cluster mesh segment");
            }
        }

        /** Borrowed read-only backing storage; ownership remains with this segment. */
        @Override
        public float[] positions() {
            return this.positions;
        }

        /** Borrowed read-only backing storage; ownership remains with this segment. */
        @Override
        public int[] primitiveRecords() {
            return this.primitiveRecords;
        }

        public int triangleCount() {
            return Math.addExact(
                    Math.addExact(this.opaqueTriangleCount, this.cutoutTriangleCount),
                    this.transmissiveTriangleCount);
        }
    }
}
