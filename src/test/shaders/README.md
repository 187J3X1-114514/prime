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
public state, sample, evaluate, volume-stack, and ray-distance contracts. A
separate parallel-slab batch sends the same direction through entry and exit
interfaces, checking reciprocal relative IOR, radiance eta scaling, Snell
round-trip direction, total internal reflection, and medium-stack lifecycle.
The entry points exercise Prime's adapter wrappers, including exiting-interface
state correction and zero-event Fresnel handling, while the protected RoboCute port remains
unchanged. The
test runner can bind the authoritative transmission-energy table as a real
immutable 3D `rgba16f` sampled image. `RoboCuteLutGpuTest` samples every texel
center and compares all four half-float channels with the vendored raw resource.

`RoboCuteDistributionGpuTest` uses a fixed large GPU batch for four representative
BSDF configurations. It compares equal-solid-angle PDF quadrature with unit
mass, performs a pooled chi-square histogram test (including an invalid/rejected
bin), and compares sampled and independently evaluated Monte Carlo energy with
the quadrature reference.

`PrimeBsdfGpuTest` includes the production `bsdf.glsl` adapter, not only the
protected reference fragments. It sweeps opaque, transmissive, foliage, and
fixed primary-split entry points. It checks first-interface Snell direction,
relative IOR and complementary Fresnel response, rough-reflection
sample/eval/PDF consistency, random-independent deterministic refraction, TIR,
per-surface glass filtering, and water-only stack transitions. Rejected
proposals must become Prime's canonical zero-event sample; every accepted public
payload must be finite, nonnegative, directionally valid, and keep its
volume-stack transition within the fixed ABI capacity.

`PrimeProductionMathGpuTest` includes the same small production fragments used
by the renderer. Its concern-specific batches cover exponent-scaled throughput,
MIS and area-light PDFs, Beer-Lambert attenuation, Russian roulette, the full
16-bit LabPBR normal/specular byte domains, conductor Fresnel, NRD sanitization,
normal packing, hit-distance normalization, demodulation, radiance limits, and
FSR depth, motion, and material-mask input domains. It also executes the
production auto-exposure bin mapping, target-EV clamp, and asymmetric temporal
adaptation contract. The transport batch additionally checks the gamma-2
glass-filter endpoints, strength, and products plus outside/inside, multiple,
out-of-order, and clamped shadow-water segments. Replay capture additionally
observes the production FSR depth and motion images emitted by `nrd_motion.comp`;
Java tests cover the NRD and FSR native scalar ABI encoders without invoking a
driver or SDK.
Do not replace these tests with Java copies of GLSL formulas: a copy cannot
observe shader compiler or device floating-point behavior.

`ShaderComputeRunner` owns a minimal headless Vulkan 1.2 compute device and
executes a complete batch in one dispatch. Bindings 0 and 1 are the standard
input/output SSBOs; tests may add arbitrary 2D/3D sampled images, 2D storage
images, push constants, and explicit XYZ workgroup counts. The runner contract
itself has an executable image/push-constant round-trip test.
`AtmosphereAerialPrefixGpuTest` executes the 128-lane non-commutative optical
segment scan and compares both spectral groups with ordered near-to-far
composition.

Normal `test` excludes the tagged GPU suites. Use:

```text
gradlew shaderTest
```

to require a usable Vulkan compute device and fail instead of skipping. CI
always runs this task through the Lavapipe Vulkan compute implementation. Every
randomized property suite must use a fixed seed, print the seed and failing case
index, and include explicit boundary cases before generated cases. Properties
must observe executable behavior; source-text matching is not a correctness
test. Numerically singular sample/evaluate reconstruction regions need an
explicitly documented domain predicate rather than an arbitrarily loose global
tolerance.
