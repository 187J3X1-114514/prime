#ifndef PRIME_ATMOSPHERE_PHASE_GLSL
#define PRIME_ATMOSPHERE_PHASE_GLSL

layout(std430, set = 0, binding = 7) readonly buffer AtmospherePhaseLut {
    vec4 phaseValues[];
};

const int ATM_PHASE_BIN_COUNT = 1024;
const int ATM_PHASE_SPECIES_COUNT = 4;

// The baked table stores samples at bin centres for
// mu = 1 - 2 * ((bin + 0.5) / 1024)^3. Hillaire's phase cosine uses the
// opposite direction convention, hence (1 + phaseCosine) below. The -0.5
// restores the bin-centre convention; linear interpolation is essential near
// the sharply varying forward lobe around the sun.
vec4 atmAerosolPhaseValue(int group, int species, float phaseCosine) {
    float coordinate = pow(
            clamp(0.5 + 0.5 * phaseCosine, 0.0, 1.0),
            1.0 / 3.0);
    float bin = coordinate * float(ATM_PHASE_BIN_COUNT) - 0.5;
    int lowerBin = clamp(int(floor(bin)), 0, ATM_PHASE_BIN_COUNT - 1);
    int upperBin = min(lowerBin + 1, ATM_PHASE_BIN_COUNT - 1);
    float fraction = clamp(bin - float(lowerBin), 0.0, 1.0);
    int groupBase = group * ATM_PHASE_BIN_COUNT;
    vec4 lower = phaseValues[
            (groupBase + lowerBin) * ATM_PHASE_SPECIES_COUNT + species];
    vec4 upper = phaseValues[
            (groupBase + upperBin) * ATM_PHASE_SPECIES_COUNT + species];
    return mix(lower, upper, fraction);
}

vec4 atmAerosolPhaseScattering(
        int group,
        float altitudeKm,
        float phaseCosine) {
    vec4 result = vec4(0.0);
    for (int species = 0; species < ATM_PHASE_SPECIES_COUNT; ++species) {
        result += atmAerosolScatteringCrossSection(group, species)
                * atmAerosolDensity(species, altitudeKm)
                * atmAerosolPhaseValue(group, species, phaseCosine);
    }
    return result;
}

#endif
