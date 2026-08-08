package dev.prime.render.terrain;

import java.util.Objects;
import net.minecraft.core.SectionPos;

/**
 * Immutable CPU result of compiling one logical cluster.
 *
 * <p>Its mesh arrays transfer ownership from the compiler to the scene residency boundary and
 * are never mutated after publication.
 */
public record CompiledCluster(
        long key,
        int clusterX,
        int clusterY,
        int clusterZ,
        CpuClusterMesh mesh,
        boolean dynamic,
        float[] motionPositions) {
    public CompiledCluster(
            long key,
            int clusterX,
            int clusterY,
            int clusterZ,
            CpuClusterMesh mesh) {
        this(key, clusterX, clusterY, clusterZ, mesh, false, new float[0]);
    }

    public CompiledCluster {
        Objects.requireNonNull(mesh, "mesh");
        Objects.requireNonNull(motionPositions, "motionPositions");
        if (SectionCluster.origin(clusterX) != clusterX
                || SectionCluster.origin(clusterY) != clusterY
                || SectionCluster.origin(clusterZ) != clusterZ
                || key != SectionPos.asLong(clusterX, clusterY, clusterZ)) {
            throw new IllegalArgumentException(
                    "Compiled cluster key and origin must identify one aligned cluster");
        }
        long expectedMotionWords = dynamic ? mesh.triangleCount() * 9L : 0L;
        if (motionPositions.length != expectedMotionWords) {
            throw new IllegalArgumentException(
                    "Dynamic motion payload does not match the compiled cluster");
        }
    }

    public static CompiledCluster dynamic(
            int clusterX,
            int clusterY,
            int clusterZ,
            CpuClusterMesh mesh,
            float[] motionPositions) {
        return new CompiledCluster(
                SectionPos.asLong(clusterX, clusterY, clusterZ),
                clusterX,
                clusterY,
                clusterZ,
                mesh,
                true,
                motionPositions);
    }

    /** Borrowed read-only storage owned by the captured dynamic frame. */
    @Override
    public float[] motionPositions() {
        return this.motionPositions;
    }

    public boolean isEmpty() {
        return this.mesh.isEmpty();
    }
}
