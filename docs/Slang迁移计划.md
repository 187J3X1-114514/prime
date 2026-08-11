# Slang 迁移计划

## 目标与边界

生产 Shader 和对应测试现已完全由 Slang 构建，生产/测试 GLSL 与过渡选择器已经移除。迁移按
一个可验证的依赖层或 entry point 推进。机械适配是降低风险的默认手段，不是逐字符翻译规则；允许在
保持行为和 ABI 契约的前提下重命名、重组模块，并使用 Slang 的泛型、接口、方法、属性和强类型
聚合表达既有语义。迁移期间不同时更换算法、采样分布、队列语义或 host ABI。发现原实现问题时
登记到 `Slang迁移问题.md`，完成全部迁移后统一处理，不在迁移提交中顺手修复。

实现取舍依次为：运行效率、长期维护性、简洁。不能为了形式上的机械对应保留已证明会妨碍编译器
优化或长期维护的表示，也不能以 Slang 重组为名改变算法。连续浮点求值顺序允许变化，但必须通过
数值、分布和能量门禁；ABI、离散事件、介质栈和队列状态仍必须精确一致。

`third_party` 始终是锁定的外部参考和原生依赖，不作为 Slang 源码目录，也不因迁移而修改。
第三方接口由 Prime 自有 adapter 隔离。

迁移代理不得启动游戏、操作窗口或控制屏幕。自动验证只使用编译、SPIR-V 检查和无窗口 GPU
测试；需要交互、画面、窗口尺寸、驱动恢复或性能采样时，登记到 `Slang人工验证.md` 并设置为
人工阻碍，由用户执行和回报结果。

## 工具链契约

- 固定 Slang 2026.14.1。默认使用独立的官方 Slang 发行包，也可通过
  `-PprimeSlangCompiler=<path>` 指定同版本编译器；Vulkan SDK 1.4.357 提供 SPIR-V Tools；
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
  smoke test。CI 从官方 v2026.14.1 release 下载 Linux 包并校验固定 SHA-256；生产产物门禁
  还要求 SER 仅使用 `SPV_EXT_shader_invocation_reorder`，禁止退回 NV 方言。

工具链依据：[Slang 编译模型](https://docs.shader-slang.org/en/stable/external/slang/docs/user-guide/08-compiling.html)、
[GLSL 迁移指南](https://docs.shader-slang.org/en/stable/coming-from-glsl.html)、
[SPIR-V 目标规则](https://docs.shader-slang.org/en/stable/external/slang/docs/user-guide/a2-01-spirv-target-specific.html)。

## 最终目录与产物结构

源码按职责放入 `shaders` 下一级目录，不建立语言优先的深层目录：

```text
shaders/
  core/
  material/
  lighting/
  atmosphere/
  post/
  rt/
  realtime/
  offline/
```

所有 include/import 路径使用从 Shader 根可解析的稳定路径；include 图按真实搜索路径解析，
不再要求不同职责目录中的基础名全局唯一。生成的 Java/Slang ABI 源码位于 `build`，生成器不再
输出 GLSL。生产 SPIR-V 使用既有 canonical 资源名，host 无语言选择分支；wavefront 的普通、
subgroup queue 与 SER 变体仍由同一 Slang entry point 生成。

## BSDF 核心保护

保护对象现为带参考来源和符号清单的 BSDF 核心声明集合，而不是具体文件名。迁移从生产 adapter
的运行时入口计算传递调用闭包：闭包内声明完整机械移植，参考库中不可达的函数、类型和验证专用
死代码未移植；“不移植死代码”不授权精简已进入闭包的表达式或分支。

文件移动、拆分或合并不会解除核心保护；初轮机械迁移仍保持参考表达式、分支与采样算法，任何
后续核心修改仍需用户明确批准。不同编译器、语言或获批浮点优化后的连续结果不要求逐 bit 一致；
Prime 材质翻译、特化、资源适配和后处理保持在核心集合之外。

`shaders/bsdf/core/robocute_reachable_symbols.txt` 记录运行时可达声明与参考版本，
`shaders/robocute.lock.json` 锁定核心文件、符号清单和 author overlay。迁移期 bit 差分已经用于
定位非预期差异；当前门禁以 Slang 行为、分布、能量和 ABI 为准。旧 GLSL port 已删除，
`third_party` 的文件与哈希锁原样保留。

## 浮点与数值验收

ABI 和离散语义保持精确：descriptor、push constant、结构布局、payload location、事件 flags、
介质栈变化和队列状态不得产生容差差异。连续浮点结果允许因编译器和有测量依据的优化产生偏差，
但每个迁移或浮点优化批次必须重跑以下 GPU 行为门禁：

- `RoboCuteCoreGpuTest`：NaN/±Inf、边界随机数、掠射角、临界折射、各向异性粗糙度、PDF、
  单位方向、对称性与互易性；
- `RoboCuteClosureGpuTest` 与 `PrimeBsdfGpuTest`：所有生产可达 closure/adapter 的有限性、
  非负响应、采样/求值一致性、事件、relative eta、介质栈和极端传播距离；
- `RoboCuteDistributionGpuTest`：采样直方图与 PDF 的统计一致性，以及 Monte Carlo 能量与
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
5. **已完成——BSDF 当前可达核心与 adapter**：冻结生产调用闭包，机械移植核心，再迁移 Prime BSDF
   adapter；使用既有 GPU 性质、分布、能量和状态测试做 GLSL/Slang 行为对照，不移植闭包外
   死代码。bit 对照只用于定位差异。
6. **已完成——大气、灯光、积分器与 SHARC adapter**：迁移 atmosphere、light tree、lights、
   integrator、queued PSR 和 Prime 自有 SHARC 适配；`third_party/sharc` 保持不变。
7. **已完成——wavefront entry points**：先 realtime、后 offline。保持当前执行模式队列、stage
   specialization、SER/subgroup 变体和调度顺序，仅替换语言实现；按 stage 单独比较 ABI、
   寄存器和图像结果。
8. **自动部分已完成——默认切换与清理**：所有 pipeline 默认使用 Slang，完整自动 GPU 门禁通过，
   已建立新 BSDF 核心锁并删除生产/测试 GLSL 和过渡选择器。画面、窗口/驱动稳定性和性能采样
   仍是人工阻碍，通过后才结束迁移目标。

每一步都同时迁移对应测试，不把正确性验证集中到最后。迁移导致的编译、ABI 或行为阻碍应在
当前最小模块边界解决；原实现中已经存在的问题只登记，不在迁移过程中修复。完成所有生产和测试
入口切换、删除过渡选择器并通过自动门禁后，才统一处理登记问题和人工验证清单。
