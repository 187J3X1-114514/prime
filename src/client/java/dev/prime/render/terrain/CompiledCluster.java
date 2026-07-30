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
        boolean dynamic) {
    public static final long DYNAMIC_KEY = Long.MAX_VALUE;

    public CompiledCluster(
            long key,
            int clusterX,
            int clusterY,
            int clusterZ,
            CpuClusterMesh mesh) {
        this(key, clusterX, clusterY, clusterZ, mesh, false);
    }

    public CompiledCluster {
        Objects.requireNonNull(mesh, "mesh");
        if (SectionCluster.origin(clusterX) != clusterX
                || SectionCluster.origin(clusterY) != clusterY
                || SectionCluster.origin(clusterZ) != clusterZ
                || (dynamic
                        ? key != DYNAMIC_KEY
                        : key != SectionPos.asLong(clusterX, clusterY, clusterZ))) {
            throw new IllegalArgumentException(
                    "Compiled cluster key and origin must identify one aligned cluster");
        }
    }

    public static CompiledCluster dynamic(
            int clusterX, int clusterY, int clusterZ, CpuClusterMesh mesh) {
        return new CompiledCluster(
                DYNAMIC_KEY, clusterX, clusterY, clusterZ, mesh, true);
    }

    public boolean isEmpty() {
        return this.mesh.isEmpty();
    }
}
