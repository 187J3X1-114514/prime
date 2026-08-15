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

`shaders/bsdf/core/robocute_*.slang` 中列入
`shaders/bsdf/core/robocute_reachable_symbols.txt` 的声明构成受保护核心。保护随声明移动，不
依赖文件名；表达式、求值顺序、分支、采样分布和中间精度只允许规定范围内的机械语言适配。
任何语义、浮点或格式化修改都必须先获得用户对具体声明、目的和范围的明确批准。不同编译器
或获批优化后的输出不要求逐 bit 一致，但必须满足数值、分布、能量和状态门禁。

Prime 自有的材质翻译、资源绑定、状态转换、诊断和回归入口位于参考核心之外；生产 Shader
使用 `shaders/bsdf/compact` 中数学等价的专用 OpenPBR 状态与公式，不导入受保护核心。厚介质
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

- `verifyRoboCutePort` 验证锁定 commit、overlay 路径、参考文件和 LUT 哈希；
- `verifyShaderIncludeGraph` 拒绝生产 Shader 导入受保护 RoboCute 模块或 reference specialization；
- `compileSlangShaders` 使用固定 SDK 的 `slangc` 直接生成 SPIR-V，以 `spirv-val` 验证，并
  由 `verifySlangRayPayloadAbi` 与生成 ABI 门禁检查跨阶段契约；
- `shaderTest` 覆盖 slab 两界面、入射/出射、Snell、TIR、sample/eval/PDF、薄壁/厚壁和
  eta-aware Russian roulette；`PrimeBsdfDiagnosticsGpuTest` 另验证 adapter 拒绝/净化前的
  NaN、Inf、负值和非法方向观察；
- `verifyDistributionJar` 检查发行 JAR 中的 Shader、LUT 和许可归属。

常用命令见[构建与验证](构建与验证.md)。如果参考一致性要求与 Prime 特有行为冲突，应在
参考文件之外增加适配层；若确实必须修改受保护文件，先报告参考位置、影响和最小范围并等待
明确批准。
