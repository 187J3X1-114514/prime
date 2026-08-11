# Slang 迁移问题登记

本文件只记录迁移过程中发现、但不属于完成语言迁移所必需修复的原有问题。迁移阻碍（编译失败、
ABI 不一致、迁移引入的行为差异）必须在对应迁移阶段解决，不放入本表逃避处理。

全部生产和测试 Shader 已完成 Slang 切换。本表项目未混入迁移实现，后续按运行效率、
正确性风险、长期维护成本排序统一处理。

| ID | 模块 | 现象与证据 | 影响 | 建议后续方向 | 状态 |
|---|---|---|---|---|---|
| MIG-001 | 大气 epipolar 投影 | `atmEpipolarProjectDirection` 每次调用都从 `inverseViewProjection` 重新求逆；Slang 机械迁移为展开的 4×4 Gauss-Jordan，证实该成本位于运行时路径。 | aerial/合成调用会重复进行与帧内不变量等价的矩阵求逆。 | 评估由 CPU 直接提供 view-projection，或在每帧 GPU 常量阶段预计算；需要同步 ABI，故本轮不改。 | 待后续算法批次处理 |
| MIG-002 | BSDF 适配层诊断 | 适配层内部仍使用无显式状态的单参数诊断钩子；Slang 生产 integrator 无法安全地用可变全局量承载逐 invocation 状态，因此当前钩子为空，所有被消费的 BSDF/PDF/方向/eta 由 integrator 边界显式扫描。 | 适配器内部产生后又被拒绝或净化的异常值不会进入生产原始诊断元数据；公开结果和实际输运状态仍受扫描与净化。 | 把诊断对象纳入 BSDF 适配上下文，重新跑 NaN/Inf、统计分布和边界拒绝测试，再决定是否保留内部细粒度字段。 | 待后续算法批次处理 |
| MIG-003 | SER SPIR-V 方言 | Slang 2026.8 会把 typed `HitObject` 路径混合降为 NV/EXT 方言，虽可编译但持续调度时触发过设备丢失。升级到 2026.14.1 后，高层 `HitObject`/`ReorderThread` API 一致生成 `SPV_EXT_shader_invocation_reorder`。 | 主机和 Shader 现在只协商标准 `VK_EXT_ray_tracing_invocation_reorder`；payload surface=0/shadow=1、调度算法和 SBT 契约未改变。 | 产物门禁拒绝任何 NV SER extension/instruction，并要求至少一个生产 SER 模块实际含 EXT reorder；继续用 `spirv-val`、GPU 属性测试和持续运行验证回归。 | 已解决，待长期运行复验 |
