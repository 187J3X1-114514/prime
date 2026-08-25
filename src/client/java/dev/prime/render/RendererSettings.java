package dev.prime.render;

import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.terrain.TerrainWorkerSettings;
import dev.prime.render.terrain.VoxelSurfaceSettings;
import java.util.Objects;

/** Immutable renderer configuration captured once at the client frame boundary. */
public record RendererSettings(
        boolean pathTracingEnabled,
        SurfaceDetailMode surfaceDetailMode,
        int voxelTextureSurfaceStrengthSteps,
        PostProcessingMode postProcessingMode,
        ReconstructionQualityMode reconstructionQuality,
        AstronomySettings astronomy,
        LightingSettings.Snapshot lighting,
        MaterialSettings.Snapshot material,
        DisplaySettings.Snapshot display,
        int scatterCount,
        int deltaWalkLimit,
        int terrainWorkerPercentage,
        long revision) {
    public RendererSettings(
            boolean pathTracingEnabled,
            SurfaceDetailMode surfaceDetailMode,
            int voxelTextureSurfaceStrengthSteps,
            PostProcessingMode postProcessingMode,
            ReconstructionQualityMode reconstructionQuality,
            AstronomySettings astronomy,
            LightingSettings.Snapshot lighting,
            MaterialSettings.Snapshot material,
            DisplaySettings.Snapshot display,
            long revision) {
        this(
                pathTracingEnabled,
                surfaceDetailMode,
                voxelTextureSurfaceStrengthSteps, postProcessingMode, reconstructionQuality,
                astronomy, lighting, material, display, ScatterSettings.DEFAULT_COUNT,
                DeltaWalkSettings.DEFAULT_LIMIT,
                TerrainWorkerSettings.DEFAULT_PERCENTAGE,
                revision);
    }

    public RendererSettings(
            boolean pathTracingEnabled,
            SurfaceDetailMode surfaceDetailMode,
            int voxelTextureSurfaceStrengthSteps,
            PostProcessingMode postProcessingMode,
            ReconstructionQualityMode reconstructionQuality,
            AstronomySettings astronomy,
            LightingSettings.Snapshot lighting,
            MaterialSettings.Snapshot material,
            DisplaySettings.Snapshot display,
            int scatterCount,
            long revision) {
        this(
                pathTracingEnabled,
                surfaceDetailMode,
                voxelTextureSurfaceStrengthSteps, postProcessingMode, reconstructionQuality,
                astronomy, lighting, material, display, scatterCount,
                DeltaWalkSettings.DEFAULT_LIMIT,
                TerrainWorkerSettings.DEFAULT_PERCENTAGE,
                revision);
    }

    public RendererSettings(
            boolean pathTracingEnabled,
            SurfaceDetailMode surfaceDetailMode,
            int voxelTextureSurfaceStrengthSteps,
            PostProcessingMode postProcessingMode,
            ReconstructionQualityMode reconstructionQuality,
            AstronomySettings astronomy,
            LightingSettings.Snapshot lighting,
            MaterialSettings.Snapshot material,
            DisplaySettings.Snapshot display,
            int scatterCount,
            int terrainWorkerPercentage,
            long revision) {
        this(
                pathTracingEnabled,
                surfaceDetailMode,
                voxelTextureSurfaceStrengthSteps, postProcessingMode, reconstructionQuality,
                astronomy, lighting, material, display, scatterCount,
                DeltaWalkSettings.DEFAULT_LIMIT,
                terrainWorkerPercentage,
                revision);
    }

    public RendererSettings {
        postProcessingMode = Objects.requireNonNull(postProcessingMode, "postProcessingMode");
        reconstructionQuality = Objects.requireNonNull(
                reconstructionQuality, "reconstructionQuality");
        astronomy = Objects.requireNonNull(astronomy, "astronomy");
        lighting = Objects.requireNonNull(lighting, "lighting");
        material = Objects.requireNonNull(material, "material");
        display = Objects.requireNonNull(display, "display");
        surfaceDetailMode = Objects.requireNonNull(surfaceDetailMode, "surfaceDetailMode");
        VoxelSurfaceSettings.maximumHeight(voxelTextureSurfaceStrengthSteps);
        ScatterSettings.validateCount(scatterCount);
        DeltaWalkSettings.validateLimit(deltaWalkLimit);
        TerrainWorkerSettings.validatePercentage(terrainWorkerPercentage);
        if (revision < 0L) {
            throw new IllegalArgumentException("Renderer settings revision must not be negative");
        }
    }

    public float voxelTextureSurfaceMaximumHeight() {
        return VoxelSurfaceSettings.maximumHeight(this.voxelTextureSurfaceStrengthSteps);
    }

    public boolean usesResourceNormals() {
        return this.surfaceDetailMode.usesResourceNormals();
    }

    public boolean usesGeometryDisplacement() {
        return this.surfaceDetailMode.usesGeometryDisplacement();
    }
}
