# Prime

Prime 是一个面向 Minecraft 的客户端 Shader Mod，基于 Minecraft Vulkan 图形后端与 Vulkan KHR 硬件光线追踪 API，定位为无偏路径追踪渲染方案。项目旨在以硬件加速的光线追踪管线重建世界渲染路径，并为后续以一致、清晰且可扩展的方式实现基于物理的光传输计算提供基础。这里的“无偏”采用工程意义：估计器不引入真实场景下可稳定观察或统计区分的系统性明暗、色彩偏差，同时接受有限精度、射线偏移和异常反弹上限等实际渲染器不可避免的近似。

当前版本是 `0.1.0`，处于积分器基线阶段。当前材质与光源只是用于验证光传输数学和 Minecraft 接入的内部适配层，不定义最终产品的材质、天空、时间或灯光模型。

## 当前积分器基线

- 完整使用 Vulkan KHR ray tracing pipeline。raygen 以迭代 mega-kernel 推进路径，miss、closest-hit 和 any-hit 只返回遍历结果；管线递归深度保持为 1。
- 每像素每帧追踪一个样本。主表面的漫反射光传输被解调后交给随包提供的 NRD 4.17.4 `REBLUR_DIFFUSE`；镜面、发光面、相机直见天空等不属于该信号的稳定分量继续保存在独立 RGBA32F 历史中，最终在线性空间重新组合。这是当前的低成本降噪边界，不把 NRD 侵入积分器或 Vulkan 资源所有权。
- NRD 使用主表面的世界空间法线、线性粗糙度、view-Z、命中距离和统一的低偏差帧抖动进行重投影。当前地形静止，因此世界空间运动向量严格为零；相机运动由当前/上一帧矩阵描述。窗口尺寸变化会整体重建与尺寸相关的 NRD 图像和历史，不复用不兼容资源。
- 当前缺省材质是由方块纹理与生物群系 tint 驱动的粗糙介质边界与漫反射基底；光源来自光谱大气、太阳和从原版发光等级/纹理估计的方块面光源。它们仍是可替换的内部适配层，不定义最终产品材质或灯光模型。
- 积分器、材质、光源、路径吞吐和 RGBA32F 累积统一使用 D65 白点的线性 Rec.2020 工作空间。Minecraft 方块纹理与 tint 在材质边界从 sRGB 解码并转换。累积完成后，独立的显示变换边界将 HDR 工作空间映射到目标显示设备；当前 sRGB Rec.709 默认采用 Oklab DRT，高光压缩前的曝光乘数硬编码为 `1.0`。显示变换只作用于一次性 RGBA8_UNORM 输出，不写回累积历史。该工作空间是积分器 ABI 契约，而不是可由单个 shader 局部修改的显示选项。
- 线性化是光传输正确性的一部分，而非单纯的显示校色。早期实现曾直接在 sRGB 非线性编码值上进行 BSDF、光源和累积运算，导致乘法、求和与平均不再对应辐射度计算，并表现为暗角系统性偏亮；改为在线性 Rec.2020 中积分后该问题才真正消失。
- 太阳与方块面光源使用下一事件估计和 power-heuristic MIS；路径正常由 miss 或带吞吐补偿的 Russian roulette 结束。256 次反弹保留为异常路径的安全上限。
- 物理着色点与 Vulkan 遍历偏移原点分离。所有路径坐标仍相对于 Prime 的渲染原点，未退回绝对世界 `float` 坐标。
- `PathState`、`SurfaceInteraction`、BSDF、光源样本、PDF 和采样维度均为显式契约。当前不实现 wavefront，但未来可以替换调度与队列层而不重写积分器数学。

## 设计边界

- `RayTracingRuntime` 是唯一生命周期入口，提供不可用、等待世界、流式构建、已接管和失败回退状态。
- `render/vulkan` 独占 Vulkan 句柄、VMA 分配、同步、SBT、管线和加速结构所有权。
- `render/terrain` 负责不可变 Section 快照的异步网格化、有界任务队列、BLAS 驻留和 TLAS 场景替换。
- `shaders/abi.json` 是 Java、GLSL 布局、积分器颜色空间与默认显示设备/变换的唯一契约声明源。构建会生成双方代码，再编译并验证全部 SPIR-V。
- `shaders/bsdf.glsl`、`material.glsl`、`lights.glsl` 和 `sampling.glsl` 定义可独立替换的积分器语义；`integrator.glsl` 负责当前 mega-kernel 调度。
- `shaders/nrd_common.glsl` 与 `render/vulkan/nrd` 共同定义 NRD 信号、原生桥接和 Vulkan 调度边界。NRD Core 只返回 SPIR-V 与调度描述，不接触 Minecraft 的 Vulkan 句柄；图像、descriptor、命令记录、同步和按真实提交完成点回收均由 Prime 持有。
- `shaders/display_transform.glsl` 是工作空间到显示设备的独立语义边界。显示变换由 NRD 后的计算合成阶段调用；大气透视也在降噪完成后加入，避免空间滤波破坏距离相关的介质项。
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

`compileShaders` 会把 Prime 自有 shader 的临时 SPIR-V 写入 `build/` 并执行 `spirv-val`。JAR 包含生成的 SPIR-V；仓库不单独保存这些生成文件。发行 JAR 另内置 Windows x86-64 的 `prime_nrd.dll`，用户无需另外寻找 NRD。该 DLL 只包含固定版本的 NRD Core、其 SPIR-V 和 Prime 的窄 C ABI；重建方法见 `native/nrd/README.md`。

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

自动测试覆盖 ABI 大小和偏移、NRD 原生 ABI/版本/SPIR-V/调度描述、颜色空间契约与往返转换、Oklab 显示变换参考检查点、显示范围和累积边界、SBT 对齐、UV/tint/法线编码、Section generation token、CPU 网格布局、渲染原点重定位、采样流、MIS 正反向权重、Russian roulette 吞吐补偿、累积历史状态，以及漫反射在常量环境下的统计收敛。构建以 Java 25 的全部编译警告为错误。

## 许可

Prime 自有代码使用 MIT 许可证。见 [LICENSE](LICENSE)。随发行物提供的 NVIDIA NRD
原生组件仍受 NVIDIA RTX SDKs License 约束，不属于 MIT 许可范围；详情见
`THIRD_PARTY_LICENSES/NRD-LICENSE.txt`。

默认粗糙介质的 GGX 方向能量特化包含源自 RoboCute 的 Apache 2.0 授权数据与模型；
归属和许可文本见 `THIRD_PARTY_LICENSES/ROBOCUTE-NOTICE.txt` 与
`THIRD_PARTY_LICENSES/APACHE-2.0.txt`。

## Co-Authored-By

- OpenAI Codex
