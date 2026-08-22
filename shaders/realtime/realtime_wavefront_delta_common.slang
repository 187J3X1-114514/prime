#ifndef PRIME_REALTIME_WAVEFRONT_DELTA_COMMON_SLANG
#define PRIME_REALTIME_WAVEFRONT_DELTA_COMMON_SLANG

// The primary-chain megakernel deliberately excludes the complete lighting integrator. Keep this
// closure limited to traversal, exact delta transport, guide state and queue publication.
import "prime_realtime_abi.slang";
import "core/camera.slang";
import "core/numerical_diagnostics.slang";
import "core/nrd_common.slang";
import "core/packed_geometry.slang";
import "core/render_config.slang";
import "core/sampling.slang";

#include "integrator/transport_core.slang"
#include "realtime/wavefront_state.slang"

#if defined(PRIME_ENABLE_SUBGROUP_QUEUE)
typealias PrimeWavefrontContinuationAppender =
        PrimeSubgroupWavefrontContinuationAppender;
#else
typealias PrimeWavefrontContinuationAppender =
        PrimeScalarWavefrontContinuationAppender;
#endif

// SER may repack invocations after traversal, so post-trace queue publication remains scalar.
#if defined(PRIME_ENABLE_SUBGROUP_QUEUE) && !defined(PRIME_ENABLE_SER)
typealias PrimePostTraceWavefrontContinuationAppender =
        PrimeSubgroupWavefrontContinuationAppender;
#else
typealias PrimePostTraceWavefrontContinuationAppender =
        PrimeScalarWavefrontContinuationAppender;
#endif

#endif
