# Integrator numerical stability audit

This audit covers the path-estimator expressions in `bsdf.glsl`, `integrator.glsl` and
`lights.glsl`. Denoiser resource conversion remains an output boundary and is not part of the
reference estimator.

## Estimator contract

RoboCute evaluates the projected BSDF response

`r(wi, wo) = f(wi, wo) * abs(cos(theta_o))`.

Prime now carries `response` and the complete proposal `pdf` separately through the BSDF boundary.
Path advancement applies `throughput * response / pdf` once. Direct lighting applies
`radiance * response * MIS / lightPdf` once. The previous adapter divided the response by cosine
and the integrator multiplied the same cosine back; it also formed `response / pdf` before the
result met the path throughput. Both identities widened the finite intermediate range without
changing the estimator.

No contribution is discarded because it is negative, NaN or infinite. Realtime accumulation and
screenshot accumulation consume every estimator sample. The raw numerical view remains
observational: classification cannot alter transport, sampling, accumulation or history.

## Stable identities

| Expression | Stable form | Reason |
| --- | --- | --- |
| `f = response / cosine`, then `f * cosine` | carry `response` | removes a canceling division and multiplication |
| `(response / pdf) * throughput` | exponent-scaled `throughput * response / pdf` | preserves a representable result across intermediate overflow or underflow |
| `a² / (a² + b²)` | ratio against the larger PDF | squares only values in `[0, 1]` |
| `powerA * distanceB²` vs. `powerB * distanceA²` | divide powers and distances by common maxima first | common positive factors cancel from the branch probability |
| `areaPdf * distance² / cosine` | exponent-scaled product/quotient | avoids an artificial `FLT_MAX` saturation |
| Gram determinant and inverse for emitter coordinates | cross-product coordinates over `dot(cross, cross)` | avoids cancellation between two large, nearly equal Gram products |
| conductor `0.5 * (sqrt(t0² + q) + t0)` for negative `t0` | `0.5 * q / (sqrt(t0² + q) - t0)` | retains the small nonnegative root |

Power-of-two exponent transfer uses `frexp` and `ldexp`. It introduces no clamp or fallback value;
if the mathematical result is outside binary32, the final operation still produces the native
overflow or underflow and the diagnostic view can observe it.

## Removed guard behavior

- BSDF evaluation and sampling no longer replace non-finite responses or PDFs with an empty lobe.
- Path throughput, medium attenuation and radiance accumulation no longer reject a contribution.
- Relative IOR no longer divides by an epsilon substitute; translated dielectric IOR is strictly
  positive by construction.
- Area-light PDF conversion no longer saturates at `FLT_MAX`.
- Screenshot accumulation no longer skips failed samples or changes its denominator.
- Empty BSDF samples use the closure event sentinel. Exact zero throughput can terminate as a
  zero-contribution optimization; non-finite values do not satisfy that equality and propagate.

## Remaining engineering bias

These are deliberate pre-existing estimator limits, not numerical protection, and were not changed
in this pass:

- The configured path length is truncated to at most 256 bounces.
- The RoboCute adapter uses a two-entry nested-medium stack; paths exceeding it cannot represent
  the full medium sequence.
- Primary and shadow traversal use finite ray ranges and geometric origin/distance offsets. They can
  omit geometry inside the offset or beyond the maximum range.
- Material translation deliberately clamps or discards unsupported LabPBR encodings. This defines
  Prime's material model rather than changing sampling of the selected model.

Russian roulette is not on this list: its survival probability is clamped for variance control, but
surviving throughput is divided by the same probability, so its expectation is unchanged.

