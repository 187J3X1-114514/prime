# Performance work beyond the current exact-simplification pass

This file records worthwhile optimizations intentionally excluded from the current low-risk pass.
Most change a sampling distribution, numerical result, denoiser input, or other visible behavior
and therefore need a separate quality and bias decision. The final section lists exact changes that
are larger lifecycle or architecture projects rather than low-hanging simplifications.

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
- Replace the light-tree SAH split scan with prefix/suffix aggregates. It reduces CPU work from a
  quadratic scan over the 16 bins to a linear scan, but changes floating-point summation grouping;
  near a cost tie that can change tree topology, sampling probabilities and the sample sequence.
- Store a precomputed inverse emitter Gram matrix (and possibly its UV affine transform) in each
  light record. Reverse-PDF evaluation would lose several dot products and divisions, but CPU-side
  precomputation changes f32 rounding and expands or repacks the hot emitter ABI. Measure the
  bandwidth/ALU trade before accepting it.
- Give the transparent primary pass a transmissive-only acceleration view and reject candidates
  behind the opaque primary depth. This could avoid a full mixed-geometry traversal on most
  pixels, but separate builds/traversal can change intersection tie behavior and require a precise
  shared-depth contract, so it is not suitable for the exact-simplification branch.
- Gate the two transparent NRD instances when a GPU-generated frame mask proves that no transparent
  branch is visible. The raw estimator is unchanged, but pausing and later restarting NRD changes
  its hidden temporal state; the first reappearing glass/water frame therefore needs an explicit
  reset policy and visual validation. This can be a large win in ordinary opaque scenes because the
  current reflection and transmission denoisers are both full-resolution dispatch graphs.
- Investigate `VK_EXT_opacity_micromap` for cutout-heavy foliage. It may substantially reduce
  any-hit shader traffic in jungles, but alpha quantization, animated atlas texels, resource-pack
  reloads and unsupported-device fallback make it a separate representation with potentially
  different intersection behavior rather than an exact source simplification.

Exact but larger lifecycle/architecture work left outside the current low-risk pass:

- Keep a timeline-recycled ring of ray-tracing descriptor bundles. A TLAS swap currently creates a
  new descriptor pool/set and retires the old one so in-flight command buffers remain valid. A ring
  can update a set only after its real completion point and avoid driver allocation churn without
  changing rendering, but it needs the same rigorous ownership protocol as NRD frame bindings.
- Pack Section geometry and light records directly into staging/native output storage. The current
  branch removed redundant final copies and long-lived CPU light data, but each completed mesh still
  owns large Java primitive arrays until upload. Direct bounded storage is exact, yet belongs with
  the mesh-builder/backpressure rewrite tracked in `docs/FIXME.md`.
