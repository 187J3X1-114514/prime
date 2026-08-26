package dev.prime.render;

import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionQualityMode;
import java.util.Objects;

/** Immutable settings consumed only by the interactive renderer. */
public record RealtimeRenderSettings(
        PostProcessingMode postProcessing,
        ReconstructionQualityMode reconstructionQuality,
        LightingSettings.Snapshot lighting,
        MaterialSettings.Snapshot material,
        DisplaySettings.Snapshot display,
        int additionalSpecularBounces,
        int minimumBounces,
        int maximumBounces) {
    public RealtimeRenderSettings {
        Objects.requireNonNull(postProcessing, "postProcessing");
        Objects.requireNonNull(reconstructionQuality, "reconstructionQuality");
        Objects.requireNonNull(lighting, "lighting");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(display, "display");
        SpecularBounceSettings.validateCount(additionalSpecularBounces);
        MinimumBounceSettings.validateCount(minimumBounces);
        MaximumBounceSettings.validateCount(maximumBounces);
    }

    public static RealtimeRenderSettings capture(RendererSettings settings) {
        Objects.requireNonNull(settings, "settings");
        return new RealtimeRenderSettings(
                settings.postProcessingMode(),
                settings.reconstructionQuality(),
                settings.lighting(),
                settings.material(),
                settings.display(),
                settings.additionalSpecularBounces(),
                settings.minimumBounces(),
                settings.maximumBounces());
    }
}
