# Prime NRD native bridge

Prime ships a small versioned C ABI around NRD Core. The bridge is intentionally API-agnostic: it
returns SPIR-V, texture descriptions and per-frame dispatch descriptions while Prime retains
ownership of every Vulkan object and synchronization point.

The checked-in release DLL is rebuilt only when this bridge or the pinned NRD version changes.
From the repository root on Windows:

```powershell
cmake -S native/nrd -B build/native/nrd -G "Visual Studio 18 2026" -A x64 `
  -DNRD_SOURCE_DIR="C:/path/to/NRD"
cmake --build build/native/nrd --config Release --target prime_nrd --parallel
```

The build is deliberately pinned to NRD 4.17.4 source commit
`9a3fe938a7558fd16b6c91a1c0456305cdcd9f16`. Before configuring, apply
`third_party/nrd/nrd-4.17.4-reblur-sh-transient.patch`. The pinned source accidentally declares
one extra full-resolution `RGBA16F` transient immediately before REBLUR SH's 1/16-resolution tile
surface. Its scheduler consequently binds the full-resolution surface as `gOut_Tiles`, violating
the Vulkan storage-image component contract and leaving the actual tile surface unused. The patch
removes only that orphan declaration and restores the scheduler's existing enum-to-pool mapping.

REBLUR SH1 is intentionally a three-component value, but NRD allocates its portable backing
texture as `RGBA16F`. DXC consequently emits formatless three-component `OpImageWrite`
instructions, which violate Vulkan's storage-image component contract when Prime binds the
four-component view. `NrdSpirv` repairs those writes at Prime's native-adapter boundary by adding
an unused fourth component. NRD reads SH1 as `xyz`, so this does not change denoising math or
require a source modification to the pinned SDK.

Prime builds SPIR-V only, with one NRD instance containing two
`REBLUR_DIFFUSE_SPECULAR_SH` denoisers and one `SIGMA_SHADOW`, no NRI and no quad-intrinsics extension.
The main REBLUR handles ordinary primary surfaces and the transmission PSR signal on transparent pixels;
the second REBLUR handles only the fixed reflection branch. Realtime rendering normally traces one complete
path per pixel. A first visible transparent interface fixes one conditional transmission path and one
conditional reflection path, reusing the interface hit and material work. No extra guide ray is traced.
The primary REBLUR uses 63 main/stabilized-history frames. The transparent-reflection REBLUR keeps
the 63-frame main ceiling for rough reflections, caps stabilization at 10 frames, and uses NRD's
roughness-responsive accumulation below 0.1 with a 3-frame floor for smooth water/glass. Both use
10 fast-history frames, a 4-frame history fix, and a 1.5 sporadic-outlier relative scale for the 1 spp
area-light and indirect signal. Direct sun on ordinary primary surfaces remains separate and
consumes SIGMA's filtered visibility at composition; transparent-interface sun reflection remains
in the dedicated reflection REBLUR so it retains the interface split.
Demodulated diffuse and specular illumination share a bounded input, and remodulated output is
bounded together with direct sun before composition. Probabilistically sampled diffuse and
specular transport use the default 30/50-pixel prepasses. Ordinary pixels use the first visible surface.
Transparent branches capture the first non-delta virtual surface's position, normal, roughness, material,
albedo, hit distance, and directional energy during their existing traversal. RR separately retains the real
visible interface and the first reflection/transmission segment distances. A bounded invocation-local
delta-chain record may fall back to the real first interface for guides only; it never truncates transport.
The A2 normal channel classifies ordinary dielectrics, metals,
transmissive interfaces and strand-like foliage; the bridge enables exact material comparison and
the foliage strand ID. REBLUR hit-distance reconstruction uses NRD's 5x5 area mode.
Copy the resulting `build/native/nrd/bin/Release/prime_nrd.dll` to
`src/client/resources/prime/natives/windows-x86_64/prime_nrd.dll` and run the full Gradle build.

NRD remains subject to the NVIDIA RTX SDKs License. Its source is not part of this repository.
