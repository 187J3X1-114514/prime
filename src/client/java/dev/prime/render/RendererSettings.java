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
        int additionalSpecularBounces,
        int minimumBounces,
        int maximumBounces,
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
                astronomy, lighting, material, display, SpecularBounceSettings.DEFAULT_COUNT,
                MinimumBounceSettings.DEFAULT_COUNT,
                MaximumBounceSettings.DEFAULT_COUNT,
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
            int maximumBounces,
            long revision) {
        this(
                pathTracingEnabled,
                surfaceDetailMode,
                voxelTextureSurfaceStrengthSteps, postProcessingMode, reconstructionQuality,
                astronomy, lighting, material, display, SpecularBounceSettings.DEFAULT_COUNT,
                MinimumBounceSettings.DEFAULT_COUNT,
                maximumBounces,
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
            int maximumBounces,
            int terrainWorkerPercentage,
            long revision) {
        this(
                pathTracingEnabled,
                surfaceDetailMode,
                voxelTextureSurfaceStrengthSteps, postProcessingMode, reconstructionQuality,
                astronomy, lighting, material, display, SpecularBounceSettings.DEFAULT_COUNT,
                MinimumBounceSettings.DEFAULT_COUNT,
                maximumBounces,
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
        SpecularBounceSettings.validateCount(additionalSpecularBounces);
        MinimumBounceSettings.validateCount(minimumBounces);
        MaximumBounceSettings.validateCount(maximumBounces);
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
