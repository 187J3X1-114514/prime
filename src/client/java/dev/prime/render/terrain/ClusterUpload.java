package dev.prime.render.terrain;

import java.util.Objects;

/** One atomic logical-cluster replacement backed by one BLAS payload. */
public record ClusterUpload(
        long key,
        int clusterX,
        int clusterY,
        int clusterZ,
        CpuClusterMesh mesh) {
    public ClusterUpload {
        Objects.requireNonNull(mesh, "mesh");
    }

    public boolean isEmpty() {
        return this.mesh.isEmpty();
    }
}
