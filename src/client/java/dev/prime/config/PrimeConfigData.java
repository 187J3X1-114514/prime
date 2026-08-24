package dev.prime.config;

import dev.prime.render.HdrOutput;
import dev.prime.render.DeltaWalkSettings;
import dev.prime.render.ScatterSettings;
import dev.prime.render.terrain.TerrainWorkerSettings;
import java.util.Objects;

/** Immutable data transferred between the properties codec and the live config owner. */
record PrimeConfigData(
        PrimeSettings settings,
        int scatterCount,
        int deltaWalkLimit,
        int terrainWorkerPercentage,
        boolean hdrEnabled,
        int referenceWhiteNits) {
    PrimeConfigData {
        Objects.requireNonNull(settings, "settings");
        scatterCount = ScatterSettings.validateCount(scatterCount);
        deltaWalkLimit = DeltaWalkSettings.validateLimit(deltaWalkLimit);
        terrainWorkerPercentage =
                TerrainWorkerSettings.validatePercentage(terrainWorkerPercentage);
        referenceWhiteNits = HdrOutput.validateReferenceWhiteNits(referenceWhiteNits);
    }

    static PrimeConfigData defaults() {
        return new PrimeConfigData(
                PrimeSettings.defaults(),
                ScatterSettings.DEFAULT_COUNT,
                DeltaWalkSettings.DEFAULT_LIMIT,
                TerrainWorkerSettings.DEFAULT_PERCENTAGE,
                false,
                HdrOutput.AUTOMATIC_REFERENCE_WHITE_NITS);
    }
}
