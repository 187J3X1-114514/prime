package dev.prime.render.terrain;

/** Explicit policy inputs for one captured-cluster translation. */
record ClusterTranslationSettings(
        boolean buildOpacityMicromap,
        int segmentTriangleTarget,
        int maxOpacityMicromapSubdivisionLevel,
        boolean voxelSurfacesEnabled,
        float voxelSurfaceMaximumHeight,
        boolean closeCoveredFluidGap,
        boolean suppressFluidFaceAgainstFullCollision) {
    ClusterTranslationSettings {
        if (segmentTriangleTarget < 2 || (segmentTriangleTarget & 1) != 0) {
            throw new IllegalArgumentException(
                    "Cluster segment capacity must contain whole quads");
        }
        if (maxOpacityMicromapSubdivisionLevel < 0) {
            throw new IllegalArgumentException(
                    "Opacity-micromap subdivision level must be nonnegative");
        }
        if (!Float.isFinite(voxelSurfaceMaximumHeight)
                || voxelSurfaceMaximumHeight < 0.0F) {
            throw new IllegalArgumentException(
                    "Voxel-surface maximum height must be finite and nonnegative");
        }
    }
}
