# Prime shader behavior tests

`compileTestShaders` compiles every test entry point in this directory with the
same glslang, SPIR-V optimization, and `spirv-val` pipeline used by runtime
shaders. The resulting modules stay under `build/generated/testShaders`; they
are never added to Prime's JAR or runtime pipeline set.

Test entry points include `prime_test_abi.glsl` and the production GLSL fragment
under test. Inputs and outputs are arrays of `uvec4`, so Java and GLSL share a
fixed 16-byte word without implicit `std430` padding. The input header contains
the case count, output stride, and optional replay case. A normal sweep writes
one failure-mask word per case. `ShaderPropertyBatch` then replays up to eight
distinct `(kind, mask)` failures with the original input and a larger witness
stride, keeping normal runs compact without losing reproducibility.

`RoboCuteCoreGpuTest` checks common samplers, Fresnel conversions and identities,
reflective and refractive microfacet paths, explicit numeric boundaries, and a
complete 16-bit-per-axis LabPBR tangent-normal domain. Valid-event results in
the documented float interior are classified for NaN and signed infinity
before algebraic, event, hemisphere, unit-length, PDF, and value properties are
checked. Boundary inputs remain in the sweep, but payloads from an invalid
sample and identities at grazing, degenerate-half-vector, eta-equals-one, or
critical-angle discontinuities are outside the numeric property domain.

`RoboCuteClosureGpuTest` exercises every Prime-reachable closure through its
public state, sample, evaluate, volume-stack, and ray-distance contracts. The
entry points exercise Prime's adapter wrappers, including the radiance eta
correction for rough and delta transmission and zero-event Fresnel handling, while the
protected RoboCute port remains unchanged. The
test runner can bind the authoritative transmission-energy table as a real
immutable 3D `rgba16f` sampled image. `RoboCuteLutGpuTest` samples every texel
center and compares all four half-float channels with the vendored raw resource.

`RoboCuteDistributionGpuTest` uses a fixed large GPU batch for four representative
BSDF configurations. It compares equal-solid-angle PDF quadrature with unit
mass, performs a pooled chi-square histogram test (including an invalid/rejected
bin), and compares sampled and independently evaluated Monte Carlo energy with
the quadrature reference.

`ShaderComputeRunner` owns a minimal headless Vulkan 1.2 compute device and
executes a complete batch in one dispatch. Normal `test` runs GPU tests when
Vulkan is available and reports them as skipped otherwise. Use:

```text
gradlew shaderTest
```

to require a usable Vulkan compute device and fail instead of skipping. Every
randomized property suite must use a fixed seed, print the seed and failing case
index, and include explicit boundary cases before generated cases. Properties
must observe executable behavior; source-text matching is not a correctness
test. Numerically singular sample/evaluate reconstruction regions need an
explicitly documented domain predicate rather than an arbitrarily loose global
tolerance.
