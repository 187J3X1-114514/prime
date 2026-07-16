# Performance ideas outside the exact-simplification branch

This file records optimizations that may be useful but are intentionally excluded from
`codex/equivalent-performance` because they change a sampling distribution, numerical result,
denoiser input, or other visible behavior. Each item needs a separate quality and bias decision.

- Replace the 256-cell per-emitter area-light alias distribution with a coarser or hierarchical
  proposal. A consistently updated PDF can keep the estimator unbiased, but the sample sequence
  and variance change, and the emission distribution would need new validation.
- Start Russian roulette earlier or make its start depth throughput-dependent. Correct survival
  compensation can remain unbiased, but it changes variance, path/sample identity and denoiser
  behavior; it is not an algebraic simplification.
- Stop the transparent delta-guide relaxation adaptively instead of always running eight forward
  and reverse sweeps. This does not change raw path radiance, but it changes PSR reprojection and
  therefore the filtered image. It needs a residual/error study around deep glass and water stacks.
- Approximate or tabulate the default rough-dielectric directional-energy fit more aggressively.
  This is a promising ALU reduction, but any replacement must be revalidated with the white-furnace
  tests because even a small fit error changes the coupled diffuse/specular energy partition.
- Use reservoir or spatiotemporal direct-light reuse in scenes with many emissive texels. This can
  greatly reduce light-tree and shadow-ray work, but it is a new estimator with visibility reuse,
  temporal state and bias questions rather than a simplification of the current one-sample NEE.
