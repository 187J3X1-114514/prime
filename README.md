# Prime

Prime 是一个面向 Minecraft 的客户端 Shader Mod，基于 Minecraft Vulkan 图形后端与 Vulkan KHR 硬件光线追踪 API，定位为无偏路径追踪渲染方案。项目旨在以硬件加速的光线追踪管线重建世界渲染路径，并为后续以一致、清晰且可扩展的方式实现基于物理的光传输计算提供基础。这里的“无偏”采用工程意义：估计器不引入真实场景下可稳定观察或统计区分的系统性明暗、色彩偏差，同时接受有限精度、射线偏移和异常反弹上限等实际渲染器不可避免的近似。

当前版本是 `0.1.0`，处于积分器基线阶段。当前材质与光源只是用于验证光传输数学和 Minecraft 接入的内部适配层，不定义最终产品的材质、天空、时间或灯光模型。

## 当前积分器基线

- 完整使用 Vulkan KHR ray tracing pipeline。raygen 以迭代 mega-kernel 推进路径，miss、closest-hit 和 any-hit 只返回遍历结果；管线递归深度保持为 1。
- 每像素每帧追踪一个样本并在独立的 RGBA32F 图像中累积。相机、已有 Section 内容、窗口、方块图集或 shader 发生变化时，历史会在下一次提交前重置。初次流送中纯新增的远处 Section 使用最多 8 个样本的短历史；场景静止 8 帧后会硬重置，再开始不混合旧场景的长期累积。
- 当前 BSDF 是由方块纹理与生物群系 tint 驱动的 Lambert 漫反射；验证光源是常量环境与强度固定为 `4.0` 的方向光。两者都是可替换的内部适配器，不定义最终灯光模型。
- 积分器、材质、光源、路径吞吐和 RGBA32F 累积统一使用 D65 白点的线性 Rec.2020 工作空间。Minecraft 方块纹理与 tint 在材质边界从 sRGB 解码并转换。累积完成后，独立的显示变换边界将 HDR 工作空间映射到目标显示设备；当前 sRGB Rec.709 默认采用 Oklab DRT，高光压缩前的曝光乘数硬编码为 `1.0`。显示变换只作用于一次性 RGBA8_UNORM 输出，不写回累积历史。该工作空间是积分器 ABI 契约，而不是可由单个 shader 局部修改的显示选项。
- 线性化是光传输正确性的一部分，而非单纯的显示校色。早期实现曾直接在 sRGB 非线性编码值上进行 BSDF、光源和累积运算，导致乘法、求和与平均不再对应辐射度计算，并表现为暗角系统性偏亮；改为在线性 Rec.2020 中积分后该问题才真正消失。
- 环境光使用下一事件估计和 power-heuristic MIS；路径正常由 miss 或带吞吐补偿的 Russian roulette 结束。256 次反弹保留为异常路径的安全上限。
- 物理着色点与 Vulkan 遍历偏移原点分离。所有路径坐标仍相对于 Prime 的渲染原点，未退回绝对世界 `float` 坐标。
- `PathState`、`SurfaceInteraction`、BSDF、光源样本、PDF 和采样维度均为显式契约。当前不实现 wavefront，但未来可以替换调度与队列层而不重写积分器数学。

## 设计边界

- `RayTracingRuntime` 是唯一生命周期入口，提供不可用、等待世界、流式构建、已接管和失败回退状态。
- `render/vulkan` 独占 Vulkan 句柄、VMA 分配、同步、SBT、管线和加速结构所有权。
- `render/terrain` 负责不可变 Section 快照的异步网格化、有界任务队列、BLAS 驻留和 TLAS 场景替换。
- `shaders/abi.json` 是 Java、GLSL 布局、积分器颜色空间与默认显示设备/变换的唯一契约声明源。构建会生成双方代码，再编译并验证全部 SPIR-V。
- `shaders/bsdf.glsl`、`material.glsl`、`lights.glsl` 和 `sampling.glsl` 定义可独立替换的积分器语义；`integrator.glsl` 负责当前 mega-kernel 调度。
- `shaders/display_transform.glsl` 是工作空间到显示设备的独立语义边界。当前为避免额外全屏 pass 而与 raygen 融合执行，但不依赖路径状态，可在以后直接拆分为独立显示阶段或替换为其他设备变换。
- Mixin 只承担 Minecraft 接入和设备能力协商；地形与 Vulkan 业务对象不持有 Mixin 对象。
- 所有运行错误均停止接管世界渲染并回到原版路径。设备不支持所需 KHR 扩展时不会请求这些扩展。

GPU 更新采用替换后提交：旧 Section/BLAS、TLAS、descriptor 和 shader pipeline 会一直有效到新版本完整建立，并通过原版 Vulkan 提交时间线延迟退休。渲染热路径不调用 `vkDeviceWaitIdle`。

地形流送会在提交模型快照前快速排除全空气 Section。失效、构建完成、待上传和工作线程队列均有明确容量；压力超过容量时会合并为全量失效或重新生成当前有界工作集，不会无限积累任务。

## 构建

需要：

- JDK 25；
- Vulkan SDK 1.3.290，且 `VULKAN_SDK` 指向安装目录；
- SDK 中可用的 `glslangValidator` 与 `spirv-val`。

Windows PowerShell 示例：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.2'
$env:VULKAN_SDK = 'C:\VulkanSDK\1.3.290.0'
$env:Path = "$env:JAVA_HOME\bin;$env:VULKAN_SDK\Bin;$env:Path"
.\gradlew.bat clean build
```

`compileShaders` 会把临时 SPIR-V 写入 `build/` 并执行 `spirv-val`。JAR 包含生成的 SPIR-V；仓库不保存二进制 shader。

## 开发运行

```powershell
.\gradlew.bat runClient
```

开发运行配置会强制 `--graphicsBackend vulkan` 并启用 Vulkan validation。使用普通发行 JAR 时，需要在 Minecraft 图形设置中选择 Vulkan 并重启。若当前为 OpenGL、设备功能不足或管线创建失败，Prime 会保留原版世界渲染并显示一次提示。

开发环境中可用下面的命令显式启动 OpenGL，以验证 Prime 不会接管世界渲染：

```powershell
.\gradlew.bat runClient -PprimeBackend=opengl
```

不要使用 Gradle 的 `--args` 覆盖后端；Loom 会将它与开发配置已有的 Vulkan 参数合并，无法得到可靠的回退测试。

## 验证

```powershell
.\gradlew.bat test compileShaders build
```

自动测试覆盖 ABI 大小和偏移、颜色空间契约与往返转换、Oklab 显示变换参考检查点、显示范围和累积边界、SBT 对齐、UV/tint/法线编码、Section generation token、CPU 网格布局、渲染原点重定位、采样流、MIS 正反向权重、Russian roulette 吞吐补偿、累积历史状态，以及漫反射在常量环境下的统计收敛。构建以 Java 25 的全部编译警告为错误。

## 许可

GPL-3.0-only。见 [LICENSE](LICENSE)。

## Co-Authored-By

- OpenAI Codex
