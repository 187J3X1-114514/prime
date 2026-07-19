package dev.prime.render.terrain;

import java.util.ArrayList;
import java.util.List;

/** Merges Section-local compiler output into one cluster-local BLAS payload. */
final class SectionClusterMeshBuilder {
    private static final int POSITION_WORDS_PER_TRIANGLE = 9;
    private static final int PRIMITIVE_WORDS_PER_TRIANGLE = CpuSectionMesh.PRIMITIVE_WORDS;
    private static final int FLAGS_EMITTER_WORD = 5;

    private final int clusterX;
    private final int clusterY;
    private final int clusterZ;
    private final List<Entry> entries = new ArrayList<>(SectionCluster.SECTION_COUNT);
    private final boolean[] populatedSections = new boolean[SectionCluster.SECTION_COUNT];
    private int opaqueTriangleCount;
    private int cutoutTriangleCount;
    private int transmissiveTriangleCount;
    private int emitterCount;

    SectionClusterMeshBuilder(int clusterX, int clusterY, int clusterZ) {
        if (SectionCluster.origin(clusterX) != clusterX
                || SectionCluster.origin(clusterY) != clusterY
                || SectionCluster.origin(clusterZ) != clusterZ) {
            throw new IllegalArgumentException("Cluster origin must be aligned to four Sections");
        }
        this.clusterX = clusterX;
        this.clusterY = clusterY;
        this.clusterZ = clusterZ;
    }

    void add(int sectionX, int sectionY, int sectionZ, CpuSectionMesh mesh) {
        long clusterKey = net.minecraft.core.SectionPos.asLong(
                this.clusterX, this.clusterY, this.clusterZ);
        if (!SectionCluster.contains(clusterKey, sectionX, sectionY, sectionZ)) {
            throw new IllegalArgumentException("Section does not belong to this cluster");
        }
        int localIndex = (sectionX - this.clusterX)
                + (sectionY - this.clusterY) * SectionCluster.SECTION_SIZE
                + (sectionZ - this.clusterZ)
                        * SectionCluster.SECTION_SIZE
                        * SectionCluster.SECTION_SIZE;
        if (this.populatedSections[localIndex]) {
            throw new IllegalArgumentException("Section was added to its cluster more than once");
        }
        this.populatedSections[localIndex] = true;
        int lightOffset = this.emitterCount;
        this.entries.add(new Entry(
                sectionX,
                sectionY,
                sectionZ,
                mesh,
                lightOffset));
        this.opaqueTriangleCount = Math.addExact(
                this.opaqueTriangleCount, mesh.opaqueTriangleCount());
        this.cutoutTriangleCount = Math.addExact(
                this.cutoutTriangleCount, mesh.cutoutTriangleCount());
        this.transmissiveTriangleCount = Math.addExact(
                this.transmissiveTriangleCount, mesh.transmissiveTriangleCount());
        this.emitterCount = Math.addExact(this.emitterCount, mesh.lights().emitterCount());
    }

    CpuSectionMesh build() {
        int triangleCount = Math.addExact(
                Math.addExact(this.opaqueTriangleCount, this.cutoutTriangleCount),
                this.transmissiveTriangleCount);
        float[] positions = new float[Math.multiplyExact(
                triangleCount, POSITION_WORDS_PER_TRIANGLE)];
        int[] primitives = new int[Math.multiplyExact(
                triangleCount, PRIMITIVE_WORDS_PER_TRIANGLE)];
        int opaquePositionCursor = 0;
        int opaquePrimitiveCursor = 0;
        int cutoutPositionCursor = Math.multiplyExact(
                this.opaqueTriangleCount, POSITION_WORDS_PER_TRIANGLE);
        int cutoutPrimitiveCursor = Math.multiplyExact(
                this.opaqueTriangleCount, PRIMITIVE_WORDS_PER_TRIANGLE);
        int transmissivePositionCursor = Math.multiplyExact(
                Math.addExact(this.opaqueTriangleCount, this.cutoutTriangleCount),
                POSITION_WORDS_PER_TRIANGLE);
        int transmissivePrimitiveCursor = Math.multiplyExact(
                Math.addExact(this.opaqueTriangleCount, this.cutoutTriangleCount),
                PRIMITIVE_WORDS_PER_TRIANGLE);
        ArrayList<CpuSectionLights.Translated> lightSources = new ArrayList<>();
        OpacityMicromapData.Builder opacityMicromap = new OpacityMicromapData.Builder();

        for (Entry entry : this.entries) {
            CpuSectionMesh mesh = entry.mesh;
            float translateX = (entry.sectionX - this.clusterX) * 16.0F;
            float translateY = (entry.sectionY - this.clusterY) * 16.0F;
            float translateZ = (entry.sectionZ - this.clusterZ) * 16.0F;
            int opaquePositionWords = Math.multiplyExact(
                    mesh.opaqueTriangleCount(), POSITION_WORDS_PER_TRIANGLE);
            int cutoutPositionWords = Math.multiplyExact(
                    mesh.cutoutTriangleCount(), POSITION_WORDS_PER_TRIANGLE);
            int transmissivePositionWords = Math.multiplyExact(
                    mesh.transmissiveTriangleCount(), POSITION_WORDS_PER_TRIANGLE);
            int opaquePrimitiveWords = Math.multiplyExact(
                    mesh.opaqueTriangleCount(), PRIMITIVE_WORDS_PER_TRIANGLE);
            int cutoutPrimitiveWords = Math.multiplyExact(
                    mesh.cutoutTriangleCount(), PRIMITIVE_WORDS_PER_TRIANGLE);
            int transmissivePrimitiveWords = Math.multiplyExact(
                    mesh.transmissiveTriangleCount(), PRIMITIVE_WORDS_PER_TRIANGLE);

            copyTranslatedPositions(
                    mesh.positions(),
                    0,
                    positions,
                    opaquePositionCursor,
                    opaquePositionWords,
                    translateX,
                    translateY,
                    translateZ);
            copyTranslatedPositions(
                    mesh.positions(),
                    opaquePositionWords,
                    positions,
                    cutoutPositionCursor,
                    cutoutPositionWords,
                    translateX,
                    translateY,
                    translateZ);
            copyPrimitives(
                    mesh.primitiveRecords(),
                    0,
                    primitives,
                    opaquePrimitiveCursor,
                    opaquePrimitiveWords,
                    entry.lightOffset);
            copyPrimitives(
                    mesh.primitiveRecords(),
                    opaquePrimitiveWords,
                    primitives,
                    cutoutPrimitiveCursor,
                    cutoutPrimitiveWords,
                    entry.lightOffset);
            copyTranslatedPositions(
                    mesh.positions(),
                    opaquePositionWords + cutoutPositionWords,
                    positions,
                    transmissivePositionCursor,
                    transmissivePositionWords,
                    translateX,
                    translateY,
                    translateZ);
            copyPrimitives(
                    mesh.primitiveRecords(),
                    opaquePrimitiveWords + cutoutPrimitiveWords,
                    primitives,
                    transmissivePrimitiveCursor,
                    transmissivePrimitiveWords,
                    entry.lightOffset);

            opaquePositionCursor += opaquePositionWords;
            opaquePrimitiveCursor += opaquePrimitiveWords;
            cutoutPositionCursor += cutoutPositionWords;
            cutoutPrimitiveCursor += cutoutPrimitiveWords;
            transmissivePositionCursor += transmissivePositionWords;
            transmissivePrimitiveCursor += transmissivePrimitiveWords;
            opacityMicromap.append(mesh.opacityMicromap());
            if (!mesh.lights().isEmpty()) {
                lightSources.add(new CpuSectionLights.Translated(
                        mesh.lights(), translateX, translateY, translateZ));
            }
        }

        CpuSectionLights lights = CpuSectionLights.merge(lightSources);
        if (lights.emitterCount() != this.emitterCount) {
            throw new IllegalStateException("Merged cluster light indices disagree with its light tree");
        }
        return new CpuSectionMesh(
                positions,
                primitives,
                this.opaqueTriangleCount,
                this.cutoutTriangleCount,
                this.transmissiveTriangleCount,
                opacityMicromap.build(),
                lights);
    }

    private static void copyTranslatedPositions(
            float[] source,
            int sourceOffset,
            float[] destination,
            int destinationOffset,
            int wordCount,
            float translateX,
            float translateY,
            float translateZ) {
        int sourceEnd = sourceOffset + wordCount;
        while (sourceOffset < sourceEnd) {
            destination[destinationOffset++] = source[sourceOffset++] + translateX;
            destination[destinationOffset++] = source[sourceOffset++] + translateY;
            destination[destinationOffset++] = source[sourceOffset++] + translateZ;
        }
    }

    private static void copyPrimitives(
            int[] source,
            int sourceOffset,
            int[] destination,
            int destinationOffset,
            int wordCount,
            int lightOffset) {
        System.arraycopy(source, sourceOffset, destination, destinationOffset, wordCount);
        int destinationEnd = destinationOffset + wordCount;
        for (int record = destinationOffset; record < destinationEnd;
                record += PRIMITIVE_WORDS_PER_TRIANGLE) {
            int packed = destination[record + FLAGS_EMITTER_WORD];
            int emitterIndex = PrimitivePacking.unpackEmitterIndex(packed);
            if (emitterIndex != PrimitivePacking.NO_EMITTER_INDEX) {
                destination[record + FLAGS_EMITTER_WORD] = PrimitivePacking.packFlagsEmitter(
                        PrimitivePacking.unpackFlags(packed),
                        Math.addExact(emitterIndex, lightOffset));
            }
        }
    }

    private record Entry(
            int sectionX,
            int sectionY,
            int sectionZ,
            CpuSectionMesh mesh,
            int lightOffset) {
    }
}
