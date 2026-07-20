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

The build is deliberately pinned to NRD 4.17.4, SPIR-V only, with one NRD instance containing
`REBLUR_DIFFUSE_SPECULAR` and `SIGMA_SHADOW`, no NRI and no quad-intrinsics extension. Realtime
rendering traces one complete path per pixel. REBLUR uses 63 main/stabilized-history frames,
10 fast-history frames and a 4-frame history fix for area-light and indirect transport. Direct sun remains a
separate signal and consumes SIGMA's filtered visibility at composition.
Demodulated diffuse and specular illumination share a bounded input, and remodulated output is
bounded together with direct sun before composition. Probabilistically sampled diffuse and
specular transport use the default 30/50-pixel prepasses. The first visible surface
supplies NRD's normal, roughness, and per-lobe directional energy. A following delta chain
multiplies specular energy into the specular guide until the first non-delta event contributes
its diffuse-plus-specular energy. The A2 normal channel classifies ordinary dielectrics, metals,
transmissive interfaces and strand-like foliage; the bridge enables exact material comparison and
the foliage strand ID. REBLUR hit-distance reconstruction uses NRD's 5x5 area mode.
Copy the resulting `build/native/nrd/bin/Release/prime_nrd.dll` to
`src/client/resources/prime/natives/windows-x86_64/prime_nrd.dll` and run the full Gradle build.

NRD remains subject to the NVIDIA RTX SDKs License. Its source is not part of this repository.
