package dev.prime.render;

import dev.prime.config.PrimeSettings;
import java.util.Objects;

/** Transport settings frozen for one offline accumulation session. */
record OfflineRenderSettings(
        LightingSettings.Snapshot lighting,
        MaterialSettings.Snapshot material,
        int maximumBounces,
        int russianRouletteStart) {
    static final int DEFAULT_MAXIMUM_BOUNCES = 128;
    static final int DEFAULT_RUSSIAN_ROULETTE_START = 1;

    OfflineRenderSettings {
        Objects.requireNonNull(lighting, "lighting");
        Objects.requireNonNull(material, "material");
        if (maximumBounces != IntegratorSettings.MAXIMUM_BOUNCES) {
            throw new IllegalArgumentException(
                    "Offline transport must use the compiled 128-bounce limit");
        }
        if (russianRouletteStart != DEFAULT_RUSSIAN_ROULETTE_START) {
            throw new IllegalArgumentException(
                    "Offline transport guarantees exactly one continuation before roulette");
        }
    }

    static OfflineRenderSettings capture(PrimeSettings settings) {
        Objects.requireNonNull(settings, "settings");
        return new OfflineRenderSettings(
                settings.lighting(),
                settings.material(),
                DEFAULT_MAXIMUM_BOUNCES,
                DEFAULT_RUSSIAN_ROULETTE_START);
    }
}
