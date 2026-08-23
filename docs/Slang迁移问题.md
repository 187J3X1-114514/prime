# Slang 迁移问题处理记录

本文件记录 Slang 迁移过程中发现、迁移完成后统一处理的原有问题。迁移阻碍（编译失败、ABI
不一致、迁移引入的行为差异）始终在对应迁移阶段解决，不进入本表逃避处理。

全部生产和测试 Shader 已完成 Slang 切换，以下登记项也已处理并通过完整 `check shaderTest`。
本表保留根因、处理边界和回归入口，避免后续维护重新引入相同问题。

| ID | 模块 | 原问题 | 处理与验证 | 状态 |
|---|---|---|---|---|
| MIG-001 | 大气 epipolar 投影 | `atmEpipolarProjectDirection` 曾在每个 invocation 从 `inverseViewProjection` 执行展开的 4×4 Gauss-Jordan 求逆。 | 直接构造求解所需的 clip x/y/w 余子式，只计算三组 4×4 determinant numerator；有限投影的比值和无穷远方向保持不变，push-constant ABI 不扩张。`AtmosphereEpipolarShadowAlignmentGpuTest` 覆盖中心、离屏、平行和旋转相机方向。 | 已解决 |
| MIG-002 | BSDF 适配层诊断 | 无显式状态的空诊断钩子无法观察 adapter 内产生后又被拒绝或净化的异常值。 | 专用测试 entry 显式启用逐 invocation `PrimeNumericalObservation`，在样本拒绝和 denoise albedo 钳制之前分类原始值；生产 adapter 只构造编译期恒禁用值，最终 `-O2` 生产 SPIR-V 必须消除观察状态与指令。`PrimeBsdfDiagnosticsGpuTest` 覆盖 NaN、Inf、负值、非法方向和禁用路径，完整 BSDF 数值/分布/能量套件通过。 | 已解决 |
| MIG-003 | SER SPIR-V 方言 | 旧 Slang 2026.8 的 typed `HitObject` 路径可能混合降为 NV/EXT 方言，并曾在持续调度中触发设备丢失。 | SDK 1.4.357.0 配套 Slang 固定生成 `SPV_EXT_shader_invocation_reorder`；host 只协商 `VK_EXT_ray_tracing_invocation_reorder`。`verifySlangRayPayloadAbi` 拒绝 NV extension/instruction、要求生产 SER 模块实际含 EXT reorder，并验证 surface=0/shadow=1 payload。最终 Slang 构建已由用户确认正常呈现并通过运行回归。 | 已解决 |
