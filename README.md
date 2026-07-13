# Prime

Prime 是一个面向 Minecraft 的客户端 Shader Mod，基于 Minecraft Vulkan 图形后端与 Vulkan KHR 硬件光线追踪 API，定位为无偏路径追踪渲染方案。项目旨在以硬件加速的光线追踪管线重建世界渲染路径，并为后续以一致、清晰且可扩展的方式实现基于物理的光传输计算提供基础。这里的“无偏”描述渲染算法的长期方向，即采样估计在统计意义上收敛于目标光传输积分，而非依赖屏幕空间近似或预设的光照技巧。

当前版本是 `0.1.0`，处于基础框架阶段。现有画面仅用于验证 Vulkan 硬件光追接入、地形流式构建和主光线纹理显示，不代表最终的路径追踪渲染结果。

## 设计边界

- `RayTracingRuntime` 是唯一生命周期入口，提供不可用、等待世界、流式构建、已接管和失败回退状态。
- `render/vulkan` 独占 Vulkan 句柄、VMA 分配、同步、SBT、管线和加速结构所有权。
- `render/terrain` 负责不可变 Section 快照的异步网格化、有界任务队列、BLAS 驻留和 TLAS 场景替换。
- `shaders/abi.json` 是 Java 与 GLSL 布局的唯一声明源。构建会生成双方代码，再编译并验证全部 SPIR-V。
- Mixin 只承担 Minecraft 接入和设备能力协商；地形与 Vulkan 业务对象不持有 Mixin 对象。
- 所有运行错误均停止接管世界渲染并回到原版路径。设备不支持所需 KHR 扩展时不会请求这些扩展。

GPU 更新采用替换后提交：旧 Section/BLAS、TLAS、descriptor 和 shader pipeline 会一直有效到新版本完整建立，并通过原版 Vulkan 提交时间线延迟退休。渲染热路径不调用 `vkDeviceWaitIdle`。

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

## 验证

```powershell
.\gradlew.bat test compileShaders build
```

自动测试覆盖 ABI 大小和偏移、SBT 对齐、UV/tint/法线编码、Section generation token、CPU 网格布局与渲染原点重定位。构建以 Java 25 的全部编译警告为错误。

## 许可

GPL-3.0-only。见 [LICENSE](LICENSE)。

## Co-Authored-By

- OpenAI Codex
