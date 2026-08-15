# Slang 迁移与现行工具链契约

## 目标与边界

生产 Shader 和对应测试现已完全由 Slang 构建，生产/测试 GLSL 与过渡选择器已经移除。本文件
保留迁移边界、现行工具链契约和完成记录，不再表示仍有语言切换阶段待执行。迁移时以可验证的
依赖层或 entry point 推进；机械适配是降低风险的默认手段，不是逐字符翻译规则。模块已在保持
行为和 ABI 契约的前提下采用 Slang 泛型、接口、方法、属性和强类型聚合。迁移期间发现的原实现
问题已在 [Slang 迁移问题处理记录](Slang迁移问题.md) 中统一关闭。

实现取舍依次为：运行效率、长期维护性、简洁。不能为了形式上的机械对应保留已证明会妨碍编译器
优化或长期维护的表示，也不能以 Slang 重组为名改变算法。连续浮点求值顺序允许变化，但必须通过
数值、分布和能量门禁；ABI、离散事件、介质栈和队列状态仍必须精确一致。

`third_party` 始终是锁定的外部参考和原生依赖，不作为 Slang 源码目录，也不因迁移而修改。
第三方接口由 Prime 自有 adapter 隔离。

迁移代理不得启动游戏、操作窗口或控制屏幕。自动验证只使用编译、SPIR-V 检查和无窗口 GPU
测试；需要交互、画面、窗口尺寸、驱动恢复或性能采样时，登记到 `Slang人工验证.md` 并设置为
人工阻碍，由用户执行和回报结果。

## 工具链契约

- 固定使用完整 Vulkan SDK 1.4.357.0 的配套 Shader 工具链：Slang
  `2026.13.1-1-g84792eb15` 与 SPIR-V Tools `v2026.3.rc1-0-gb707790a`。工具链升级必须作为
  独立任务验证，不随普通开发改动漂移；
- 每个 entry point 独立调用 `slangc`，目标为 `spirv`、profile 为 `glsl_460`、capability
  为 `spirv_1_5`，最终以 `spirv-val --target-env vulkan1.2` 为准；
- 使用直接 SPIR-V 后端。迁移比较的是最终 SPIR-V ABI 和可执行行为，不把生成 GLSL 作为
  正确性依据；
- 显式使用 Slang `-matrix-layout-row-major`。Slang 在 SPIR-V 目标上的行列术语与
  GLSL/SPIR-V 装饰相反，该选项产生现有 GLSL ABI 使用的 `ColMajor` 装饰；
- 显式使用 `-fvk-use-gl-layout` 保持 raw buffer 的 std430 规则。全局保持 Slang 默认浮点
  模式，不为追求 bit 对照启用全局 `precise`。后续只在行为测试证明默认模式破坏数值或状态
  契约时，才对最小表达式使用 `precise`；`fast` 或其他近似也必须以性能收益和完整数值门禁
  为依据，不能在机械迁移中顺手启用；
- descriptor set/binding、push constant offset、specialization constant ID、ray payload
  location 和结构 stride 继续由 SPIR-V 产物测试约束。reflection JSON 只用于诊断；
- `verifySlangToolchain` 是 `check` 的组成部分，负责版本、编译、SPIR-V 验证和最小 ABI
  smoke test。CI 安装完整的官方 Vulkan SDK 1.4.357.0；生产产物门禁还要求 SER 仅使用
  `SPV_EXT_shader_invocation_reorder`，禁止退回 NV 方言。

工具链依据：[Slang 编译模型](https://docs.shader-slang.org/en/stable/external/slang/docs/user-guide/08-compiling.html)、
[GLSL 迁移指南](https://docs.shader-slang.org/en/stable/coming-from-glsl.html)、
[SPIR-V 目标规则](https://docs.shader-slang.org/en/stable/external/slang/docs/user-guide/a2-01-spirv-target-specific.html)。

## 最终目录与产物结构

源码按职责放入 `shaders` 下一级目录，不建立语言优先的深层目录：

```text
shaders/
  bsdf/
  core/
  material/
  lighting/
  atmosphere/
  post/
  rt/
  realtime/
  offline/
```

模块依赖默认使用从 Shader 根解析的稳定 `import`。文本 include 只保留在需要共享同一编译
上下文的 wavefront entry 聚合、BSDF adapter 组合和外部 SHARC 头文件边界；依赖图按真实搜索
路径同时解析两者，不再要求不同职责目录中的基础名全局唯一。生成的 Java/Slang ABI 源码位于
`build`，生成器不再输出 GLSL。生产 SPIR-V 使用既有 canonical 资源名，host 无语言选择分支；
wavefront 的普通、subgroup queue 与 SER 变体由同一组 Slang entry point 和 specialization
control 生成。

## OpenPBR 紧凑迁移

机械 Slang core 已完成过渡任务并从仓库删除。`shaders/bsdf/compact` 按精确 OpenPBR 拓扑建立
专用状态；当前生产入口的全部材质族已经切换，未覆盖的 OpenPBR 能力在材质边界拒绝，不能
回退到参考库，避免第二套公式重新进入 SPIR-V 或测试编译单元。

`shaders/robocute.lock.json` 只锁定第三方参考版本和 author overlay，`third_party` 文件与哈希原样
保留。紧凑实现允许浮点舍入差异，但不得引入模型近似；详细覆盖范围、切换门槛和测试契约见
`docs/OpenPBR紧凑模块.md`。

## 浮点与数值验收

ABI 和离散语义保持精确：descriptor、push constant、结构布局、payload location、事件 flags、
介质栈变化和队列状态不得产生容差差异。连续浮点结果允许因编译器和有测量依据的优化产生偏差，
但每个迁移或浮点优化批次必须重跑以下 GPU 行为门禁：

- `OpenPbrCoreGpuTest` 与紧凑 topology 属性测试：NaN/±Inf、边界随机数、掠射角、
  各向异性粗糙度、PDF、单位方向、对称性、互易性、事件和状态转换；
- `PrimeBsdfGpuTest` 与 `OpenPbrTransmissionSlabGpuTest`：生产 adapter 的有限性、非负响应、
  采样/求值一致性、relative eta、介质栈、Snell/TIR 和极端传播距离；
- `PrimeBsdfDiagnosticsGpuTest`：adapter 在拒绝或净化之前对 NaN/Inf、负值与非法方向的
  显式逐 invocation 观察，以及关闭观察时的无状态边界；
- `OpenPbrDistributionGpuTest`：采样直方图与 PDF 的统计一致性，以及 Monte Carlo 能量与
  独立求积的一致性；
- `PrimeNumericalGpuTest`：以原始 IEEE-754 bit pattern 覆盖 NaN、±Inf、正负零、次正规数、
  FP16 上界、负值、单位区间和非法方向的生产分类器；
- `PrimeProductionMathGpuTest` 和其余 `shaderTest`：throughput、MIS、Beer-Lambert、RR、
  灯光 PDF、NRD/显示输入及数值诊断链路。

测试必须直接检查可执行结果中的 NaN、正无穷、负无穷、负 PDF/响应、非法方向和越界状态。
若改动触及现有参数域之外的近似、极端值或长路径传播，先扩展对应行为测试，再接受性能结果。
画面对照用于捕获组合误差，但不能替代上述性质和统计门禁。

## 迁移顺序

1. **已完成——工具链与产物门禁**：固定编译器、CI、SPIR-V validation、矩阵/descriptor/push
   constant smoke test，并以同一工具链生成生产 Slang Shader。
2. **已完成——生成 ABI 与无状态基础层**：ABI 生成器输出 Slang；迁移数值、transport、颜色、
   sampling 等叶子模块，并为每个模块复用现有数学性质测试。
3. **已完成——独立 compute/post pipeline**：native debug、auto exposure、display/FSR、NRD/RR
   等依赖面较小的 entry point 开始，建立 shadow artifact 和逐 pipeline host 切换方式。
4. **已完成——场景、材质与命中契约**：迁移材质 IR、LabPBR 翻译、介质边界、hit common、ray payload
   和 world/shadow hit/miss；先锁定结构 stride、payload location 和 any-hit 行为。
5. **已完成——BSDF 当前可达核心与 adapter**：冻结生产调用闭包，迁移 Prime BSDF adapter，
   再以精确紧凑 topology 取代过渡期机械移植；GPU 性质、分布、能量和状态测试接管验收，
   本地参考 port 已在收口阶段删除。bit 对照只用于定位历史差异。
6. **已完成——大气、灯光、积分器与 SHARC adapter**：迁移 atmosphere、light tree、lights、
   integrator、queued PSR 和 Prime 自有 SHARC 适配；`third_party/sharc` 保持不变。
7. **已完成——wavefront entry points**：先 realtime、后 offline。保持当前执行模式队列、stage
   specialization、SER/subgroup 变体和调度顺序，仅替换语言实现；按 stage 单独比较 ABI、
   寄存器和图像结果。
8. **已完成——默认切换与清理**：所有 pipeline 使用 Slang，完整自动 GPU 门禁通过，已建立
   新 BSDF 核心锁并删除生产/测试 GLSL 和过渡选择器。最大化窗口/驱动稳定性阻碍修复后，
   用户确认最终构建正常呈现并通过性能回归；迁移目标已结束。

每一步都同时迁移对应测试，没有把正确性验证集中到最后。迁移导致的编译、ABI 或行为阻碍在
当前最小模块边界解决；原实现中已经存在的问题在语言切换后单独处理。后续工具链升级、浮点模式
调整或 Shader 架构变化继续复用本文件的 ABI、数值和人工验证契约，但不重新打开已完成的迁移阶段。
