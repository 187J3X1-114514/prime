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
rendering traces one complete path per pixel. REBLUR uses 63 main-history frames, 10 fast-history
frames and a 4-frame history fix for area-light and indirect transport. Direct sun lighting is
stored unshadowed while a dedicated shadow SBT ray returns the first occluder distance; SIGMA
filters the resulting directional-light penumbra before composition. The path loop accumulates
specular albedo across preceding delta samples, multiplies the first non-delta surface's
diffuse-plus-specular albedo into that guide, and promotes the surface's normal and roughness to
NRD. REBLUR hit-distance reconstruction uses NRD's 5x5 area mode.
Copy the resulting `build/native/nrd/bin/Release/prime_nrd.dll` to
`src/client/resources/prime/natives/windows-x86_64/prime_nrd.dll` and run the full Gradle build.

NRD remains subject to the NVIDIA RTX SDKs License. Its source is not part of this repository.
