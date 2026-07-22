# RoboCute BSDF port audit

## Scope and reference

- Repository: <https://github.com/RoboCute/RoboCute>
- Fixed revision: `5985e989254b4685e3885d876b33f4874d233dcd`
- Local reference: `C:\WorkSpace\_ref\RoboCute`
- Prime baseline audited: `e610865e18aadb0937c9279d2dedfa33fd6b6265`

The local reference worktree was verified at the fixed revision. Every protected Prime function was
checked by source family, including constants, branch conditions, expression tree, evaluation order,
PDF/throughput updates, sampling remaps and volume-stack side effects.

| Prime protected file | RoboCute source families | Result |
| --- | --- | --- |
| `robocute_bsdf_common.glsl` | BSDF flags, throughput, samples, utilities, material/state records and ONB helpers | Match after mechanical GLSL/type flattening |
| `robocute_bsdf_fresnel.glsl` | base and specialized Fresnel, thin film and spectral sensitivity | Match |
| `robocute_bsdf_microfacet.glsl` | microfacet, reflective and refractive Fresnel microfacet | Match; descriptor declaration is a mechanical Vulkan binding adaptation |
| `robocute_bsdf_closures.glsl` | diffuse, specular, conductor, coat, fuzz, subsurface, transmission and diffraction closures | Match except the approved thin-wall singular limit |
| `robocute_bsdf_openpbr.glsl` | mixing, layering, OpenPBR and polymorphic specializations | Match except the approved zero-probability endpoint rule |

No protected `robocute_bsdf_*.glsl` file was modified by this audit.

## Approved current differences

1. A source `random <= branchWeight` test also requires `branchWeight > 0`. This changes only the
   zero-probability endpoint and prevents remapping `0 / 0`; every positive-probability branch keeps
   the source comparison, operations and sampling distribution.
2. The thin-wall geometric-series reciprocal returns the exact finite limit at the removable
   lossless-grazing singularity instead of evaluating `0 * Inf`.
3. GLSL syntax, flattened class/template names, equivalent intrinsics, namespace prefixes,
   descriptor declarations and binding macros are mechanical adaptations.

## Drift history before the lock

The initial port is commit `fe91ece`. Commit `c083b95` later introduced real source drift before the
lock existed:

- source `<=` sampling decisions were changed to `<`;
- smooth-specular energy was replaced with an analytic endpoint instead of the reference GGX table;
- the roughness-zero table cell was altered at runtime;
- generic OpenPBR compilation was conditionally pruned and a Prime specialization lived inside the
  imported module.

Commit `69cfdbd` restored the reference comparison, lookup-table expressions and full imported graph,
then moved the Prime-only specialization to `prime_bsdf_specializations.glsl`. The two approved
singularity rules above remained. The current protected files are the initial port plus those two
approved rules and mechanical descriptor/comment changes; the complete comparison found no remaining
unapproved expression or evaluation-order drift at the time the lock was introduced.

The lock itself was defective: the hashes added by `dd9892f` did not match the files committed beside
it, so the exact baseline failed `verifyRoboCutePort`. Verification now hashes UTF-8 content after LF
normalization and the lock records the audited content. This makes the check independent of Git's
Windows checkout line endings without weakening byte-level content protection.

## Responsibility boundary

The generic closure implementation, Fresnel, microfacet math, energy compensation, mixing/layering,
PDFs and importance sampling remain exclusively in the protected port.

- `material_translation.glsl` owns LabPBR decoding, optical constants, color-space conversion and
  conversion to RoboCute's F0/F82 parameterization. This code had leaked into `bsdf.glsl` and was
  moved during this pass.
- `bsdf.glsl` owns Prime material construction, world/local direction conversion, event translation,
  conditional glass proposals and the public response/PDF contract. It contains no second generic
  closure implementation.
- `prime_bsdf_specializations.glsl` is intentionally outside the protected port. It selects the
  reachable BaseSubstrate graph for Prime thin surfaces and duplicates the reference state-composition
  sequence to avoid initializing unreachable coat, fuzz, diffraction and thin-film layers. This is a
  source-coupled performance specialization, not an alternative BSDF calculation; it must be audited
  whenever the fixed reference revision changes.
- Conditional glass reflection/transmission selection is a Prime proposal needed to classify paths
  for reconstruction. Its selection probability is folded into the complete PDF, leaving RoboCute's
  physical response untouched.
- Denoiser albedo extraction calls the library's energy functions under restricted flags. It is an
  auxiliary reconstruction adapter and cannot feed back into radiance transport.

No generic BSDF responsibility was found outside these explicit translation, specialization and
proposal adapters.

