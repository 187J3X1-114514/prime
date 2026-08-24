#language slang 2026
module "state/realtime/sharc_training.slang";

import "prime_sharc_abi.slang";
import "state/realtime/contract.slang";

public static const uint PRIME_SHARC_TRAINING_CAMERA_GRID_SIZE = 3u;
public static const uint PRIME_SHARC_TRAINING_WALK_CHUNK = 8u;
public static const uint PRIME_SHARC_TRAINING_TERMINAL = 0x80000000u;
public static const uint PRIME_SHARC_TRAINING_JOB_COUNT_MASK = 0xffu;

public uint2 primeSharcTrainingCameraPhase(uint updatePhase) {
    return uint2(
            updatePhase % PRIME_SHARC_TRAINING_CAMERA_GRID_SIZE,
            updatePhase / PRIME_SHARC_TRAINING_CAMERA_GRID_SIZE);
}

public uint primeSharcTrainingPathCount() {
    return primeSharcFrame.trainingWidth * primeSharcFrame.trainingHeight;
}

public uint primeSharcTrainingPathIndexFromPixel(uint2 pixel) {
    uint2 coordinate = pixel / PRIME_SHARC_TRAINING_CAMERA_GRID_SIZE;
    return coordinate.y * primeSharcFrame.trainingWidth + coordinate.x;
}

public uint2 primeSharcTrainingPixelFromIndex(uint trainingIndex) {
    uint2 coordinate = uint2(
            trainingIndex % primeSharcFrame.trainingWidth,
            trainingIndex / primeSharcFrame.trainingWidth);
    return coordinate * PRIME_SHARC_TRAINING_CAMERA_GRID_SIZE
            + primeSharcTrainingCameraPhase(primeSharcFrame.updatePhase);
}

public uint primeSharcTrainingPathWalkDepth() {
    return min(
            PRIME_SHARC_TRAINING_WALK_CHUNK,
            primeWavefrontPixelCount() / primeSharcTrainingPathCount());
}

public uint primeSharcTrainingJobIndex(
        uint trainingIndex, uint localBounce) {
    return primeWavefrontPixelCount()
            + localBounce * primeSharcTrainingPathCount()
            + trainingIndex;
}

public uint primeSharcTrainingJobPathIndex(uint jobIndex) {
    return (jobIndex - primeWavefrontPixelCount())
            % primeSharcTrainingPathCount();
}

public uint2 primeSharcTrainingJobPixel(uint jobIndex) {
    return primeSharcTrainingPixelFromIndex(
            primeSharcTrainingJobPathIndex(jobIndex));
}

public uint primeSharcTrainingJobPreviousLightNormal(uint jobIndex) {
    return (primeWavefrontPaths[jobIndex].psrPacked.w
            >> PRIME_WAVEFRONT_LIGHT_NORMAL_SHIFT)
            & PRIME_WAVEFRONT_LIGHT_NORMAL_MASK;
}

public void primeStoreSharcTrainingJobDirect(
        uint jobIndex, float3 direct) {
    primeWavefrontPaths[jobIndex].psrPacked.xyz = asuint(direct);
}

public float3 primeLoadSharcTrainingJobDirect(uint jobIndex) {
    return asfloat(primeWavefrontPaths[jobIndex].psrPacked.xyz);
}
