package dev.prime.render;

import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionQualityMode;
import java.util.Objects;

/** Immutable settings consumed only by the interactive renderer. */
public record RealtimeRenderSettings(
        boolean sharcEnabled,
        PostProcessingMode postProcessing,
        ReconstructionQualityMode reconstructionQuality,
        LightingSettings.Snapshot lighting,
        MaterialSettings.Snapshot material,
        DisplaySettings.Snapshot display,
        int scatterCount,
        int primaryChainLimit) {
    public RealtimeRenderSettings {
        Objects.requireNonNull(postProcessing, "postProcessing");
        Objects.requireNonNull(reconstructionQuality, "reconstructionQuality");
        Objects.requireNonNull(lighting, "lighting");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(display, "display");
        ScatterSettings.validateCount(scatterCount);
        PrimaryChainSettings.validateLimit(primaryChainLimit);
    }

    public static RealtimeRenderSettings capture(RendererSettings settings) {
        Objects.requireNonNull(settings, "settings");
        return new RealtimeRenderSettings(
                settings.sharcEnabled(),
                settings.postProcessingMode(),
                settings.reconstructionQuality(),
                settings.lighting(),
                settings.material(),
                settings.display(),
                settings.scatterCount(),
                settings.primaryChainLimit());
    }
}
