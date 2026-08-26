package dev.prime.render;

import java.util.Objects;

/** Transport settings frozen for one offline accumulation session. */
public record OfflineRenderSettings(
        LightingSettings.Snapshot lighting,
        MaterialSettings.Snapshot material,
        int maximumBounces,
        int russianRouletteStart) {
    public static final int DEFAULT_RUSSIAN_ROULETTE_START = 1;

    public OfflineRenderSettings {
        Objects.requireNonNull(lighting, "lighting");
        Objects.requireNonNull(material, "material");
        MaximumBounceSettings.validateCount(maximumBounces);
        if (russianRouletteStart != DEFAULT_RUSSIAN_ROULETTE_START) {
            throw new IllegalArgumentException(
                    "Offline transport begins roulette at the second scatter");
        }
    }

    public static OfflineRenderSettings capture(RendererSettings settings) {
        Objects.requireNonNull(settings, "settings");
        return new OfflineRenderSettings(
                settings.lighting(),
                settings.material(),
                settings.maximumBounces(),
                DEFAULT_RUSSIAN_ROULETTE_START);
    }
}
