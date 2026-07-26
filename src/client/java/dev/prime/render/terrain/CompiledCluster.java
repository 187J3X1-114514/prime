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
        CpuClusterMesh mesh) {
    public CompiledCluster {
        Objects.requireNonNull(mesh, "mesh");
        if (SectionCluster.origin(clusterX) != clusterX
                || SectionCluster.origin(clusterY) != clusterY
                || SectionCluster.origin(clusterZ) != clusterZ
                || key != SectionPos.asLong(clusterX, clusterY, clusterZ)) {
            throw new IllegalArgumentException(
                    "Compiled cluster key and origin must identify one aligned cluster");
        }
    }

    public boolean isEmpty() {
        return this.mesh.isEmpty();
    }
}
