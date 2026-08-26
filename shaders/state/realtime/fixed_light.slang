#language slang 2026
module "state/realtime/fixed_light.slang";

import "contract/light_sample.slang";
import "prime_abi_types.slang";
import "prime_realtime_abi.slang";
import "service/light/emission.slang";
import "state/realtime/contract.slang";

public struct PrimeFixedLightSelection {
    public AreaLightSelection area;
};

public static const uint PRIME_FIXED_LIGHT_SELECTION_WORDS = 3u;

public PrimeFixedLightSelection primeDisabledFixedLightSelection() {
    PrimeFixedLightSelection selection;
    selection.area.sectionIndex = PRIME_NO_LIGHT_INDEX;
    selection.area.emitterIndex = PRIME_NO_LIGHT_INDEX;
    selection.area.pdf = -1.0;
    return selection;
}

public bool primeFixedDirectEnabled(PrimeFixedLightSelection selection) {
    return selection.area.pdf >= 0.0;
}

public uint primeFixedLightSelectionWord(uint pathIndex) {
    uint pixelCount = primeWavefrontPixelCount();
    uint guideWords = pixelCount * PRIME_WAVEFRONT_AREA_GUIDE_WORDS;
    uint surfaceWords = pixelCount
            * PRIME_WAVEFRONT_PATH_SLOTS_PER_PIXEL
            * (PRIME_WAVEFRONT_SURFACE_RECORD_SIZE / 4u);
    return guideWords + surfaceWords
            + pathIndex * PRIME_FIXED_LIGHT_SELECTION_WORDS;
}

public void primeStoreFixedLightSelection(
        uint pathIndex, PrimeFixedLightSelection selection) {
    uint word = primeFixedLightSelectionWord(pathIndex);
    primeWavefrontQueue[word + 0u] = selection.area.sectionIndex;
    primeWavefrontQueue[word + 1u] = selection.area.emitterIndex;
    primeWavefrontQueue[word + 2u] = asuint(selection.area.pdf);
}

public PrimeFixedLightSelection primeLoadFixedLightSelection(uint pathIndex) {
    uint word = primeFixedLightSelectionWord(pathIndex);
    PrimeFixedLightSelection selection;
    selection.area.sectionIndex = primeWavefrontQueue[word + 0u];
    selection.area.emitterIndex = primeWavefrontQueue[word + 1u];
    selection.area.pdf = asfloat(primeWavefrontQueue[word + 2u]);
    return selection;
}
