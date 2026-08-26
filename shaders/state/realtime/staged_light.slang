#language slang 2026
module "state/realtime/staged_light.slang";

import "contract/light_sample.slang";
import "prime_abi_types.slang";
import "prime_realtime_abi.slang";
import "service/light/emission.slang";
import "state/realtime/contract.slang";

public struct PrimeStagedLightSelection {
    public AreaLightSelection area;
};

public static const uint PRIME_STAGED_LIGHT_SELECTION_WORDS = 3u;

public PrimeStagedLightSelection primeDisabledStagedLightSelection() {
    PrimeStagedLightSelection selection;
    selection.area.sectionIndex = PRIME_NO_LIGHT_INDEX;
    selection.area.emitterIndex = PRIME_NO_LIGHT_INDEX;
    selection.area.pdf = -1.0;
    return selection;
}

public bool primeStagedDirectEnabled(PrimeStagedLightSelection selection) {
    return selection.area.pdf >= 0.0;
}

public uint primeStagedLightSelectionWord(uint pathIndex) {
    uint pixelCount = primeWavefrontPixelCount();
    uint guideWords = pixelCount * PRIME_WAVEFRONT_AREA_GUIDE_WORDS;
    uint surfaceWords = pixelCount
            * PRIME_WAVEFRONT_PATH_SLOTS_PER_PIXEL
            * (PRIME_WAVEFRONT_SURFACE_RECORD_SIZE / 4u);
    return guideWords + surfaceWords
            + pathIndex * PRIME_STAGED_LIGHT_SELECTION_WORDS;
}

public void primeStoreStagedLightSelection(
        uint pathIndex, PrimeStagedLightSelection selection) {
    uint word = primeStagedLightSelectionWord(pathIndex);
    primeWavefrontQueue[word + 0u] = selection.area.sectionIndex;
    primeWavefrontQueue[word + 1u] = selection.area.emitterIndex;
    primeWavefrontQueue[word + 2u] = asuint(selection.area.pdf);
}

public PrimeStagedLightSelection primeLoadStagedLightSelection(uint pathIndex) {
    uint word = primeStagedLightSelectionWord(pathIndex);
    PrimeStagedLightSelection selection;
    selection.area.sectionIndex = primeWavefrontQueue[word + 0u];
    selection.area.emitterIndex = primeWavefrontQueue[word + 1u];
    selection.area.pdf = asfloat(primeWavefrontQueue[word + 2u]);
    return selection;
}
