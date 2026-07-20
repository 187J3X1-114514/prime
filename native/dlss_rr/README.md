# Prime DLSS Ray Reconstruction bridge

This narrow C ABI owns NGX initialization, optimal-resolution queries, feature creation,
evaluation, and release. Prime continues to own every Vulkan resource and synchronization point.

Build on Windows x86-64 from a Visual Studio developer shell:

```powershell
cmake -S native/dlss_rr -B build/native/dlss_rr -G Ninja `
  -DDLSS_SDK_DIR=C:/WorkSpace/_ref/DLSS -DCMAKE_BUILD_TYPE=Release
cmake --build build/native/dlss_rr
```

Copy `prime_dlss_rr.dll` beside the release `nvngx_dlssd.dll` in
`src/client/resources/prime/natives/windows-x86_64`.
