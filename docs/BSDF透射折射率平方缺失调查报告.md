# RoboCute BSDF 参考与透射契约

> 本文已按当前 Slang 生产结构更新。历史 GLSL 端口只用于当时定位问题，不再是保护或构建
> 边界；规范保护规则见仓库根目录 `AGENTS.md`。

## 当前状态

Prime 锁定 RoboCute commit
`0d982c77b3fd26c2c5a3c0852be3bd05e5860bd8`。该版本已包含厚介质辐亮度传输所需的
`1/η²` 缩放；历史“玻璃变黑”问题已经修复并由 GPU 回归覆盖，不是当前开放缺陷。

RoboCute 作者于 2026-07-24 提供的 dielectric highlight energy compensation overlay 仍单独
锁定在 `third_party/robocute/author-bsdf-hotfix-2026-07-24/`。上游当前锁定版本修复了厚玻璃
传输，但未包含该替换 LUT 和分支化多次散射补偿，因此两份参考必须同时验证。

## Prime 适配边界

历史本地 RoboCute Slang port 与 `shaders/bsdf/core` 已删除。固定上游 checkout 和不可变 author
overlay 只承担行为参考与来源归属，不进入 Prime 的 Slang 编译闭包，也不作为生产回退路径。

Prime 自有的材质翻译、资源绑定、状态转换、诊断和回归入口使用
`shaders/bsdf/compact` 中数学等价的专用 OpenPBR 状态与公式。厚介质
出射时，紧凑实现保留以有符号局部半球选择相对 IOR 的契约，使 Snell 方向、TIR、Fresnel、PDF、
`1/η²` 和返回的 `relativeEta` 使用同一个事件方向。真实 entering 状态仍由 Prime 体积栈消费。

`relativeEta` 的用途分为两类，不能混淆：

- 辐亮度 BSDF response 中的 `1/η²` 属于传输闭包本身；
- path-state `etaScale` 只修正 Russian roulette 的存活度量，不写回真实 throughput。

实时积分器在首接口以 50/50 棋盘格选择一个条件反射或条件透射事件；光滑折射由
确定性适配显式应用与事件 `relativeEta` 一致的 `/η²`，粗糙条件透射则使用完整闭包在透射
半球内归一化的 sample/eval/PDF。后续透明顶点与离线 renderer 使用紧凑 OpenPBR 透射闭包
的随机单分支，因此 `/η²`、事件 PDF、`relativeEta` 和介质栈契约都进入生产路径。

## 自动门禁

- `verifyRoboCuteReference` 验证锁定 commit、overlay 路径、文件集合和 LUT 哈希；
- `verifyShaderIncludeGraph` 拒绝恢复历史 `shaders/bsdf/core` 或 reference specialization，并检查
  现存 Slang 依赖可解析且无环；
- `compileSlangShaders` 使用固定 SDK 的 `slangc` 直接生成 SPIR-V，以 `spirv-val` 验证，并
  由 `verifySlangRayPayloadAbi` 与生成 ABI 门禁检查跨阶段契约；
- `shaderTest` 覆盖 slab 两界面、入射/出射、Snell、TIR、sample/eval/PDF、薄壁/厚壁和
  eta-aware Russian roulette；`PrimeBsdfDiagnosticsGpuTest` 另验证 adapter 拒绝/净化前的
  NaN、Inf、负值和非法方向观察；
- `verifyDistributionJar` 检查发行 JAR 中的 Shader、LUT 和许可归属。

常用命令见[构建与验证](构建与验证.md)。如果参考一致性要求与 Prime 特有行为冲突，应在
compact 或 adapter 中明确隔离 Prime 契约；不得修改 `third_party` 或恢复第二套本地参考实现。
