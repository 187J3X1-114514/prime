package dev.prime.render.terrain;

/** One indivisible virtual-chunk replacement. */
public record ClusterUpload(long key, int clusterX, int clusterY, int clusterZ, CpuSectionMesh mesh) {
}
