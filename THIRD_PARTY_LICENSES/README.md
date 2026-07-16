# Third-party licenses

- FidelityFX SDK 1.1.4 (FSR 3.1.4 Upscaler shader source, with the FSR 3.1.5 RCAS negative-output
  correction backported from SDK 2.0): MIT License. See
  `FIDELITYFX-SDK-LICENSE.txt`. Prime vendors only the platform-independent GPU headers and the
  eight upscaler passes plus the official debug view; frame interpolation is not included or used.

Prime releases include a compiled NVIDIA Real-time Denoisers (NRD) component.
That component is not covered by Prime's MIT license. It remains subject to the
NVIDIA RTX SDKs License in `NRD-LICENSE.txt`.

NVIDIA, the NVIDIA logo, and NVIDIA Real-time Denoisers (NRD) are trademarks
and/or registered trademarks of NVIDIA Corporation in the United States and
other countries.

Prime's OpenPBR closure library and transmission-GGX energy data are derived
from RoboCute's Apache-2.0-licensed BSDF implementation. See
`ROBOCUTE-NOTICE.txt` and `APACHE-2.0.txt`.
