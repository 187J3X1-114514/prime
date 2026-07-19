# Prime

Prime 是一个面向 Minecraft 的客户端 Shader Mod，基于 Minecraft Vulkan 图形后端与 Vulkan KHR 硬件光线追踪 API。项目以可游玩的实时路径追踪和可收敛的高质量截图渲染为两个明确边界：实时模式允许为帧率采用可解释的近似与时间滤波，截图模式则保留无偏估计器作为一等公民。这里的“无偏”采用工程意义：估计器不引入真实场景下可稳定观察或统计区分的系统性明暗、色彩偏差，同时接受有限精度、射线偏移和异常反弹上限等实际渲染器不可避免的近似。

当前版本是 `0.1.0`，处于积分器基线阶段。当前材质与光源只是用于验证光传输数学和 Minecraft 接入的内部适配层，不定义最终产品的材质、天空、时间或灯光模型。

## 当前积分器基线

- “视频设置”中的“截图模式”会冻结进入时的相机、太阳、地形、灯光标定、摄像机介质状态与方块动画，在窗口原生分辨率每像素追踪一条完整 BSDF 路径，并直接累积到线性 Rec.2020 RGBA32F 历史。该路径不运行 NRD 或 FSR，也不对高亮样本做钳制；显示时只读取当前统计均值并执行同一 Oklab DRT。退出后会恢复地形流送并进行一次完整重同步。窗口纵横比变化会保留冻结的位置与朝向、更新投影并重新开始累积；世界/资源切换则安全退出模式，避免在同一均值中混合不同场景。该开关仅在当前游戏会话有效。
- 完整使用 Vulkan KHR ray tracing pipeline。raygen 以迭代 mega-kernel 推进路径，miss、closest-hit 和 any-hit 只返回遍历结果；管线递归深度保持为 1。支持通用 `VK_EXT_ray_tracing_invocation_reorder` 且报告真实重排模式的设备会在表面遍历后对 shader 续体做可选重排；普通 permutation 保留原始行为。
- 默认启用 AMD FidelityFX FSR 3.1.4 Upscaler，并采用 Performance（每轴 2.0×）模式：路径追踪与 NRD 在较低分辨率运行，去噪后的线性 Rec.2020 HDR 场景由 AMD 官方签名的 Vulkan DLL 执行时间超分辨率与弱 RCAS，最后才进入 Prime 的 Oklab 显示变换。主光线使用模型 UV 微分驱动的 ray-cone LOD，并应用当前质量档对应的 mip bias，以减少远处纹理的时域混叠。原版“视频设置”中的 Prime 区域可实时选择 Native AA（1.0×）、Quality（1.5×）、Balanced（1.7×）、Performance（2.0×）或 Ultra Performance（3.0×），也可开启 FidelityFX 接入调试总览；切换质量会成组重建尺寸相关资源和时间历史，并保存到 `config/prime.properties`。DLL 接收 Minecraft 已有的 Vulkan 设备、命令缓冲和外部图像，自行持有完整 FSR 管线及私有时间资源；Prime 仍负责跨边界同步、资源生命周期和颜色管理。该接入不包含光流、交换链代理或任何插帧路径，目前与 NRD 一样随 JAR 提供 Windows x86-64 原生库，但不要求 AMD 显卡。
- 每像素每帧只追踪一条完整 BSDF 路径，并只运行一套 NRD 4.17.4 `REBLUR_DIFFUSE_SPECULAR`。PT 反弹循环以 `hitted_non_delta=false` 开始，沿透明 delta 链继续推进；首次采到非 delta 事件时锁定降噪法线与线性粗糙度，并把此前路径吞吐乘入该表面的漫反射/镜面材质因子。漫反射与镜面光传输去调制后进入同一套历史，命中距离重建使用 5×5 区域。透明材质不再确定性拆成反射、透射两条路径，也不再拥有额外的降噪实例、虚拟表面历史或透明合成 pass。
- NRD 使用主表面的世界空间法线、线性粗糙度、view-Z、命中距离和 FSR 的统一 Halton 帧抖动进行重投影。运动采用 NRD 推荐的非抖动 2.5D 屏幕空间约定：`old = new + MV`，其中 XY 指向上一帧 UV，Z 为上一帧与当前帧 view-Z 之差；FSR 复用其 XY，并接收独立的 reversed-infinite depth。天空运动只包含视角旋转而忽略平移。窗口尺寸变化会整体重建与尺寸相关的 NRD/FSR 图像和历史，不复用不兼容资源。
- 当前缺省不透明材质是由方块纹理与生物群系 tint 驱动的粗糙介质边界与漫反射基底；在线性 Rec.2020 中以纹理 Y 亮度把线性粗糙度严格限制在 `0.70–0.90`，让亮像素获得更集中的高光，同时让暗像素保持粗糙。Section 网格同时提取 solid、cutout、translucent 模型层与原版流体（包括 waterlogged 流体）；玻璃类完整方块、薄壁透明模型和水分别进入完整的 RoboCute 介质透射 BSDF，使用运行时 3D 方向能量表、Fresnel/折射以及跨反弹保存的两层体积栈。缺省透明材质的线性粗糙度固定为零，由单条路径按 Fresnel 分布采样 delta 反射或透射；未来显式提供非零粗糙度的材质仍使用完整 GGX 重要性采样。零体积薄壁使用闭包的精确光滑双界面级数。吸收严格按射线实际经过的介质段应用；摄像机位于真实水面以下时会以水介质初始化体积栈。光源来自光谱大气、太阳和从原版发光等级/纹理估计的方块面光源。它们仍是可替换的内部适配层，不定义最终产品材质或灯光模型。
- 资源包声明 `format=lab-pbr/1.3` 时，Prime 会为方块图集建立同布局的 `_n`/`_s` 辅助图集，并完整解码 LabPBR 1.3 的八个通道。当前参与着色与采样的内容包括感知光滑度/线性粗糙度、介电 F0、预定义与自定义金属、次表面散射权重和发光强度；切线空间法线已经读取并重建，但本阶段按设计继续使用几何法线，AO、高度和孔隙率则只保留明确语义，等待对应的微观遮蔽、位移和天气系统。材质贴图提供的发光会逐像素覆盖原版发光语义，并进入现有两级灯光树、下一事件估计与 MIS。透明与不透明材质共享同一 RoboCute 闭包和重要性采样边界。辅助贴图跟随原版图集的 mip、padding、自定义帧顺序和插值进度；资源包重载会原子替换 GPU 图集并使受影响的 Section 重新构建。
- 积分器、材质、光源、路径吞吐和 RGBA32F 累积统一使用 D65 白点的线性 Rec.2020 工作空间。Minecraft 方块纹理与 tint 在材质边界从 sRGB 解码并转换。累积完成后，独立的显示变换边界将 HDR 工作空间映射到目标显示设备；当前 sRGB Rec.709 默认采用 Oklab DRT，高光压缩前的曝光乘数硬编码为 `1.0`。显示变换只作用于一次性 RGBA8_UNORM 输出，不写回累积历史。该工作空间是积分器 ABI 契约，而不是可由单个 shader 局部修改的显示选项。
- 线性化是光传输正确性的一部分，而非单纯的显示校色。早期实现曾直接在 sRGB 非线性编码值上进行 BSDF、光源和累积运算，导致乘法、求和与平均不再对应辐射度计算，并表现为暗角系统性偏亮；改为在线性 Rec.2020 中积分后该问题才真正消失。
- 太阳与方块面光源使用下一事件估计和 power-heuristic MIS；路径正常由 miss 或带吞吐补偿的 Russian roulette 结束。256 次反弹保留为异常路径的安全上限。
- 物理着色点与 Vulkan 遍历偏移原点分离。所有路径坐标仍相对于 Prime 的渲染原点，未退回绝对世界 `float` 坐标。
- `PathState`、`SurfaceInteraction`、BSDF、光源样本、PDF 和采样维度均为显式契约。当前不实现 wavefront，但未来可以替换调度与队列层而不重写积分器数学。

## 设计边界

- `RayTracingRuntime` 是唯一生命周期入口，提供不可用、等待世界、流式构建、已接管和失败回退状态。
- `render/vulkan` 独占 Vulkan 句柄、VMA 分配、同步、SBT、管线和加速结构所有权。
- `render/terrain` 负责不可变 Section 快照的异步网格化、有界任务队列、BLAS 驻留和 TLAS 场景替换。第二个 BLAS geometry 是“需要 any-hit 的表面”而非单纯 cutout：alpha coverage 与真实透射仍由 primitive flag 严格区分。
- `shaders/abi.json` 是 Java、GLSL 布局、积分器颜色空间与默认显示设备/变换的唯一契约声明源。构建会生成双方代码，再编译并验证全部 SPIR-V。
- `shaders/bsdf.glsl`、`material.glsl`、`lights.glsl` 和 `sampling.glsl` 定义可独立替换的积分器语义；`integrator.glsl` 负责当前 mega-kernel 调度。
- `shaders/nrd_common.glsl` 与 `render/vulkan/nrd` 共同定义 NRD 信号、原生桥接和 Vulkan 调度边界。NRD Core 只返回 SPIR-V 与调度描述，不接触 Minecraft 的 Vulkan 句柄；图像、descriptor、命令记录、同步和按真实提交完成点回收均由 Prime 持有。
- `shaders/display_transform.glsl` 是工作空间到显示设备的独立语义边界。实时路径的大气透视在 NRD 后、FSR 前的线性 HDR 合成中加入，显示变换严格位于 FSR 之后；截图路径则在每个样本进入 RGBA32F 均值前加入固定的大气透视，并从均值直接显示。两条路径的历史都不会混入非线性的显示设备编码值。
- Mixin 只承担 Minecraft 接入和设备能力协商；地形与 Vulkan 业务对象不持有 Mixin 对象。
- 所有运行错误均停止接管世界渲染并回到原版路径。设备不支持所需 KHR 扩展时不会请求这些扩展。

GPU 更新采用替换后提交：旧 Section/BLAS、TLAS、descriptor 和 shader pipeline 会一直有效到新版本完整建立，并通过原版 Vulkan 提交时间线延迟退休。渲染热路径不调用 `vkDeviceWaitIdle`。

地形流送会在提交模型快照前快速排除全空气 Section。失效、构建完成、待上传和工作线程队列均有明确容量；压力超过容量时会合并为全量失效或重新生成当前有界工作集，不会无限积累任务。

## 构建

需要：

- JDK 25；
- Vulkan SDK 1.4.350，且 `VULKAN_SDK` 指向安装目录；该版本用于编译并验证通用
  `VK_EXT_ray_tracing_invocation_reorder` 的可选 SER shader permutation；
- SDK 中可用的 `glslangValidator` 与 `spirv-val`。

Windows PowerShell 示例：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.2'
$env:VULKAN_SDK = 'C:\VulkanSDK\1.4.350.0'
$env:Path = "$env:JAVA_HOME\bin;$env:VULKAN_SDK\Bin;$env:Path"
.\gradlew.bat clean build
```

`compileShaders` 会编译 Prime 自有 shader，并从同一份 mega-kernel 源码生成普通与 SER 两个 raygen permutation；运行时只在设备报告真实 invocation reorder 能力时加载后者。发布/日常开发构建会剥离 SPIR-V 调试信息、执行保持语义的结构化简并再次运行 `spirv-val`，以降低冷启动时的驱动编译成本；需要在 Nsight 中查看 shader 源码映射时可显式传入 `-PprimeShaderDebug=true`，该模式保留完整调试信息且跳过发布化简。运行时将实时与截图两个 raygen group 放在同一个 Vulkan ray tracing pipeline 中，共享 miss/hit 编译结果；截图模式不会在进入游戏后临时编译另一套管线。临时 SPIR-V 写入 `build/`，JAR 包含生成结果，仓库不单独保存二进制 SPIR-V。FSR 的 shader、管线和私有资源由随包提供的 AMD 官方 `amd_fidelityfx_vk.dll` 持有，不再由 Gradle 编译或打入独立 FSR SPIR-V。发行 JAR 同时内置 Windows x86-64 的 `prime_nrd.dll`；用户无需另外寻找 NRD 或 FidelityFX 文件。NRD DLL 只包含固定版本的 NRD Core、其 SPIR-V 和 Prime 的窄 C ABI；重建方法见 `native/nrd/README.md`。

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

### 视频设置与诊断

原版“视频设置”的 Prime 区域集中管理截图模式、FSR 质量、阳光强度、方块灯光强度以及 NRD/FSR 调试视图，并提供一个“恢复 Prime 默认设置”按钮。FSR 质量是 Native AA、Quality、Balanced、Performance、Ultra Performance 五档离散滑条；两项灯光强度是相对默认标定的 EV 偏移，每档 `0.25 EV`，按 `2^EV` 精确换算为线性辐射亮度。除仅对当前会话生效的截图模式外，其余选择都会保存到 `config/prime.properties`，且不占用游戏快捷键。

NRD 调试视图包含：

1. `NRD validation`：NRD 自带的 4×4 验证视口。视口从左上角的 0 开始编号，重点观察标号 3 的运动向量误差、标号 4 的世界网格/相机抖动，以及标号 8/11 的漫反射/镜面历史长度。
2. `reprojection error`：用 raygen 保存的实际主命中点独立复投影，并与提交给 NRD 的运动向量比较。黑色表示吻合；红/青表示正/负 X 误差，绿/品红表示正/负 Y 误差，白色表示深度误差。满色约等于 4 像素或 4 个世界单位的偏差。
3. `motion vectors`：直接显示提交给 NRD 的运动。XY 使用与上一项相同的有符号配色，白色为 view-Z 变化；满色约等于 4 像素或 4 个世界单位的运动。

诊断时应按“完全静止、只旋转视角、沿世界轴平移、前后/上下移动、最后恢复视角晃动”的顺序测试。前两种诊断画面不会替换或重新计算被检查的运动数据，因此能区分坐标约定错误、相机矩阵错误和 NRD 参数错误，而不是继续依靠目测鬼影方向试错。

## 验证

```powershell
.\gradlew.bat test compileShaders build
```

自动测试覆盖 ABI 大小和偏移、NRD 原生 ABI/版本/SPIR-V/漫反射与镜面调度描述、颜色空间契约与往返转换、Oklab 显示变换参考检查点、显示范围和累积边界、截图模式状态与无降噪完整路径契约、SBT 对齐、UV/tint/法线/透明材质标志编码、Section generation token、CPU 网格布局、渲染原点重定位、采样流、MIS 正反向权重、Russian roulette 吞吐补偿、RoboCute 透射查找表与持久体积栈接入、累积历史状态，以及漫反射在常量环境下的统计收敛。构建以 Java 25 的全部编译警告为错误。

`build` 还会检查发行 JAR 的完整性：Fabric 元数据、许可证、NRD DLL、FidelityFX DLL 和 Prime shader 必须齐全，验证专用 shader 与 GLSL 源文件不得混入发行物。两个 DLL 都会检查 PE 文件头；Windows x86-64 测试还会实际加载它们并验证导出入口，其他构建平台会跳过原生执行测试。GitHub Actions 使用 Linux 完整编译 Java 和 Prime shader、执行其余测试并检查最终 JAR。

## 许可

Prime 自有代码使用 MIT 许可证。见 [LICENSE](LICENSE)。随发行物提供的 NVIDIA NRD
原生组件仍受 NVIDIA RTX SDKs License 约束，不属于 MIT 许可范围；详情见
`THIRD_PARTY_LICENSES/NRD-LICENSE.txt`。

默认粗糙介质的 GGX 方向能量特化包含源自 RoboCute 的 Apache 2.0 授权数据与模型；
归属和许可文本见 `THIRD_PARTY_LICENSES/ROBOCUTE-NOTICE.txt` 与
`THIRD_PARTY_LICENSES/APACHE-2.0.txt`。

随包提供的 AMD FidelityFX SDK 1.1.4 Vulkan DLL 包含 FSR 3.1.4 Upscaler，按 FidelityFX SDK 的 MIT 许可证分发。Prime 只调用 Upscaler API，不接入 Frame Generation 或交换链替换。许可文本见 `THIRD_PARTY_LICENSES/FIDELITYFX-SDK-LICENSE.txt`。

## Co-Authored-By

- OpenAI Codex
