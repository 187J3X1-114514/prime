package dev.prime.config;

import dev.prime.render.HdrOutput;
import dev.prime.render.MaximumBounceSettings;
import dev.prime.render.MinimumBounceSettings;
import dev.prime.render.SpecularBounceSettings;
import dev.prime.render.terrain.TerrainWorkerSettings;
import java.util.Objects;

/** Immutable data transferred between the properties codec and the live config owner. */
record PrimeConfigData(
        PrimeSettings settings,
        int additionalSpecularBounces,
        int minimumBounces,
        int maximumBounces,
        int terrainWorkerPercentage,
        boolean hdrEnabled,
        int referenceWhiteNits) {
    PrimeConfigData {
        Objects.requireNonNull(settings, "settings");
        additionalSpecularBounces = SpecularBounceSettings.validateCount(additionalSpecularBounces);
        minimumBounces = MinimumBounceSettings.validateCount(minimumBounces);
        maximumBounces = MaximumBounceSettings.validateCount(maximumBounces);
        terrainWorkerPercentage =
                TerrainWorkerSettings.validatePercentage(terrainWorkerPercentage);
        referenceWhiteNits = HdrOutput.validateReferenceWhiteNits(referenceWhiteNits);
    }

    static PrimeConfigData defaults() {
        return new PrimeConfigData(
                PrimeSettings.defaults(),
                SpecularBounceSettings.DEFAULT_COUNT,
                MinimumBounceSettings.DEFAULT_COUNT,
                MaximumBounceSettings.DEFAULT_COUNT,
                TerrainWorkerSettings.DEFAULT_PERCENTAGE,
                false,
                HdrOutput.AUTOMATIC_REFERENCE_WHITE_NITS);
    }
}
