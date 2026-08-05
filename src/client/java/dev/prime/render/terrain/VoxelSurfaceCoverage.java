package dev.prime.render.terrain;

/** Pure spatial policy for the near-camera texture-voxel window. */
public final class VoxelSurfaceCoverage {
    private VoxelSurfaceCoverage() {}

    public static boolean changes(
            int clusterX,
            int clusterY,
            int clusterZ,
            int oldCenterSectionX,
            int oldCenterSectionY,
            int oldCenterSectionZ,
            int newCenterSectionX,
            int newCenterSectionY,
            int newCenterSectionZ) {
        return includes(
                        clusterX,
                        clusterY,
                        clusterZ,
                        oldCenterSectionX,
                        oldCenterSectionY,
                        oldCenterSectionZ)
                != includes(
                        clusterX,
                        clusterY,
                        clusterZ,
                        newCenterSectionX,
                        newCenterSectionY,
                        newCenterSectionZ);
    }

    public static boolean includes(
            int clusterX,
            int clusterY,
            int clusterZ,
            int centerSectionX,
            int centerSectionY,
            int centerSectionZ) {
        int centerClusterX = SectionCluster.origin(centerSectionX);
        int centerClusterY = SectionCluster.origin(centerSectionY);
        int centerClusterZ = SectionCluster.origin(centerSectionZ);
        return Math.abs(clusterX - centerClusterX) <= SectionCluster.SECTION_SIZE
                && Math.abs(clusterY - centerClusterY) <= SectionCluster.SECTION_SIZE
                && Math.abs(clusterZ - centerClusterZ) <= SectionCluster.SECTION_SIZE;
    }
}
