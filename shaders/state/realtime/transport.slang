#language slang 2026
module "state/realtime/transport.slang";

import "state/realtime/contract.slang";
import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "state/path.slang";
import "contract/realtime/guide.slang";
import "math/queued_psr.slang";
import "math/packed_geometry.slang";
import "math/transport.slang";
import "state/realtime/medium.slang";
import "model/realtime/transport.slang";
import "bsdf/compact/contract/volume.slang";
import "contract/path.slang";

public uint primePackWavefrontPathControl(PathState path) {
    // FP16 etaScale only changes the roulette probability; survivor reweighting preserves the
    // estimator's expectation. Medium extinction and IOR remain FP32 in the transport record.
    return (min(primePathBounce(path.control), PRIME_WAVEFRONT_BYTE_MASK)
                    << PRIME_WAVEFRONT_BOUNCE_SHIFT)
            | ((primePackWavefrontEtaScale(path.etaScale)
                    & PRIME_WAVEFRONT_ETA_SCALE_MASK)
                    << PRIME_WAVEFRONT_ETA_SCALE_SHIFT)
            | ((primePathFlags(path.control)
                    & PRIME_WAVEFRONT_PATH_FLAGS_MASK)
                    << PRIME_WAVEFRONT_PATH_FLAGS_SHIFT);
}

public bool primeWavefrontActive(PrimeWavefrontTransportRecord record) {
    uint denoiserControl =
            asuint(record.rayDirectionAndDenoiserControl.w);
    return (denoiserControl & PRIME_WAVEFRONT_ACTIVE_MASK) != 0u;
}

public PrimeWavefrontTransportRecord primeWavefrontWithActive(
        PrimeWavefrontTransportRecord record, bool enabled) {
    uint denoiserControl =
            asuint(record.rayDirectionAndDenoiserControl.w);
    if (enabled) {
        denoiserControl |= PRIME_WAVEFRONT_ACTIVE_MASK;
    } else {
        denoiserControl &= ~PRIME_WAVEFRONT_ACTIVE_MASK;
    }
    record.rayDirectionAndDenoiserControl.w = asfloat(denoiserControl);
    return record;
}

public PrimeWavefrontTransportRecord primeWavefrontWithQueuedPsrControl(
        PrimeWavefrontTransportRecord record,
        uint control) {
    uint pathControl = record.sourceAndPathControl.w;
    pathControl &= ~(PRIME_WAVEFRONT_PSR_CONTROL_MASK
            << PRIME_WAVEFRONT_PSR_CONTROL_SHIFT);
    pathControl |= (control & PRIME_WAVEFRONT_PSR_CONTROL_MASK)
            << PRIME_WAVEFRONT_PSR_CONTROL_SHIFT;
    record.sourceAndPathControl.w = pathControl;
    return record;
}

public PrimeWavefrontTransportRecord primeWavefrontWithGuideFallback(
        PrimeWavefrontTransportRecord record,
        bool fallback) {
    if (fallback) {
        record.sourceAndPathControl.w |= PRIME_WAVEFRONT_GUIDE_FALLBACK;
    } else {
        record.sourceAndPathControl.w &= ~PRIME_WAVEFRONT_GUIDE_FALLBACK;
    }
    return record;
}

public uint4 primePackWavefrontPsrState(PrimeQueuedPsrState state) {
    float3 firstDirection = state.firstDirectionLength.xyz;
    if (!(dot(firstDirection, firstDirection) > 0.0)) {
        firstDirection = float3(0.0, 0.0, 1.0);
    }
    return uint4(
            primePackOctahedralNormal(firstDirection),
            asuint(state.firstDirectionLength.w),
            packSnorm2x16(clamp(state.rotation.xy, float2(-1.0), float2(1.0))),
            packSnorm2x16(clamp(state.rotation.zw, float2(-1.0), float2(1.0))));
}

public PrimeWavefrontPathRecord primeWavefrontWithQueuedPsrState(
        PrimeWavefrontPathRecord record,
        PrimeQueuedPsrState state) {
    record.transport = primeWavefrontWithQueuedPsrControl(
            record.transport, state.control);
    record.psrPacked = primePackWavefrontPsrState(state);
    return record;
}

public uint primeLoadWavefrontPreviousLightNormal(uint pathIndex) {
    return (primeWavefrontPaths[pathIndex].psrPacked.w
            >> PRIME_WAVEFRONT_LIGHT_NORMAL_SHIFT)
            & PRIME_WAVEFRONT_LIGHT_NORMAL_MASK;
}

public PrimeWavefrontPathRecord primeWavefrontWithPreviousLightNormal(
        PrimeWavefrontPathRecord record, uint packedNormal) {
    record.psrPacked.w = (record.psrPacked.w & PRIME_WAVEFRONT_LIGHT_NORMAL_MASK)
            | ((packedNormal & PRIME_WAVEFRONT_LIGHT_NORMAL_MASK)
                    << PRIME_WAVEFRONT_LIGHT_NORMAL_SHIFT);
    return record;
}

public PrimeWavefrontPathRecord primeWavefrontWithPrimaryPreviousPosition(
        PrimeWavefrontPathRecord record, PrimeDenoiserGuides guides) {
    record.psrPacked.xyz = asuint(guides.primaryHasMotion
            ? guides.primaryPreviousPosition
            : guides.primaryPosition);
    record.psrPacked.w = (record.psrPacked.w & ~PRIME_WAVEFRONT_PRIMARY_MOTION)
            | (guides.primaryHasMotion ? PRIME_WAVEFRONT_PRIMARY_MOTION : 0u);
    return record;
}

public void primeStoreWavefrontPreviousLightNormal(
        uint pathIndex, uint packedNormal) {
    uint packed = primeWavefrontPaths[pathIndex].psrPacked.w;
    primeWavefrontPaths[pathIndex].psrPacked.w =
            (packed & PRIME_WAVEFRONT_LIGHT_NORMAL_MASK)
            | ((packedNormal & PRIME_WAVEFRONT_LIGHT_NORMAL_MASK)
                    << PRIME_WAVEFRONT_LIGHT_NORMAL_SHIFT);
}

public PrimeQueuedPsrState primeLoadWavefrontPsrState(
        uint pathIndex,
        PrimeWavefrontTransportRecord record) {
    uint4 packed = primeWavefrontPaths[pathIndex].psrPacked;
    PrimeQueuedPsrState state;
    state.firstDirectionLength = float4(
            primeUnpackOctahedralNormal(packed.x),
            asfloat(packed.y));
    state.rotation = float4(
            unpackSnorm2x16ToFloat(packed.z),
            unpackSnorm2x16ToFloat(packed.w));
    float rotationLengthSquared = dot(state.rotation, state.rotation);
    state.rotation = rotationLengthSquared > 0.0
            ? state.rotation * rsqrt(rotationLengthSquared)
            : float4(0.0, 0.0, 0.0, 1.0);
    uint pathControl = record.sourceAndPathControl.w;
    state.control = (pathControl >> PRIME_WAVEFRONT_PSR_CONTROL_SHIFT)
            & PRIME_WAVEFRONT_PSR_CONTROL_MASK;
    return state;
}

public void primeStoreWavefrontPsrState(
        uint pathIndex,
        PrimeQueuedPsrState state) {
    primeWavefrontPaths[pathIndex].psrPacked =
            primePackWavefrontPsrState(state);
}

public PrimeWavefrontTransportRecord primeMakeWavefrontTransportRecord(
        PathState path,
        PrimeOpenPbrVolumeStack volumeStack,
        PrimeDenoiserState denoiserState,
        uint primaryMaterialFlags,
        bool enabled) {
    PrimeWavefrontTransportRecord record;
    record.physicalOriginAndPreviousBsdfPdf =
            float4(path.physicalOrigin, path.previousBsdfPdf);
    record.sourceAndPathControl = uint4(
            path.sourcePrimitive,
            0u,
            primePackWavefrontPathControl(path));
    record.medium0 = primePackWavefrontMedium(volumeStack.values[0]);
    record.medium1 = primePackWavefrontMedium(volumeStack.values[1]);
    uint denoiserControl = enabled ? PRIME_WAVEFRONT_ACTIVE_MASK : 0u;
    if (denoiserState.reachedSolidAngle) {
        denoiserControl |= PRIME_WAVEFRONT_REACHED_SOLID_ANGLE;
    }
    if (denoiserState.diffusePath) {
        denoiserControl |= PRIME_WAVEFRONT_DIFFUSE_PATH;
    }
    denoiserControl = denoiserControl
            | (min(denoiserState.primaryBounce, PRIME_WAVEFRONT_BYTE_MASK)
                    << PRIME_WAVEFRONT_PRIMARY_BOUNCE_SHIFT)
            | (min(volumeStack.count, 2u)
                    << PRIME_WAVEFRONT_MEDIUM_COUNT_SHIFT)
            | ((primaryMaterialFlags & PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK)
                    << PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT);
    record.rayDirectionAndDenoiserControl =
            float4(path.rayDirection, asfloat(denoiserControl));
    record.throughputAndReserved = float4(path.throughput, 0.0);
    return record;
}

public PrimeWavefrontPathRecord primeMakeWavefrontRecord(
        PathState path,
        PrimeOpenPbrVolumeStack volumeStack,
        PrimeDenoiserState denoiserState,
        PrimeDenoiserGuides guides,
        bool enabled) {
    PrimeWavefrontPathRecord record;
    record.transport = primeMakeWavefrontTransportRecord(
            path,
            volumeStack,
            denoiserState,
            guides.primaryMaterialFlags,
            enabled);
    record = primeWavefrontWithQueuedPsrState(
            record, primeEmptyQueuedPsrState());
    record = primeWavefrontWithPreviousLightNormal(
            record, primePathPreviousLightNormal(path.control));
    record = primeWavefrontWithPrimaryPreviousPosition(record, guides);
    return record;
}
