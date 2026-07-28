# Prime

Prime 是 Minecraft 26.2 的客户端路径追踪 Shader Mod，使用 Vulkan KHR 硬件光线追踪。
它提供可游玩的实时路径追踪，以及独立的高质量累积截图模式。

> Prime 仍处于早期开发阶段。画质、性能和兼容性会继续变化，不建议在没有备份的情况下
> 将它用于重要存档。

[下载发行版](https://github.com/bWFuanVzYWth/prime/releases) ·
[报告问题](https://github.com/bWFuanVzYWth/prime/issues) ·
[已知问题](docs/FIXME.md)

## 主要功能

- 实时 1 spp 路径追踪，包括太阳、天空、夜间星空和发光方块照明；
- 表面滤色玻璃、体积吸收水体、金属和粗糙表面；
- 玻璃与水可滤光而不直接遮断太阳和方块光阴影射线；
- 支持 DLSS Ray Reconstruction，并在不可用时回退到 NRD + FSR；
- 支持部分 LabPBR 1.3 材质；
- 原生分辨率的参考累积截图；
- 可在游戏内切换原版渲染与 Prime，无需退出游戏。

## 下载与运行要求

当前发行版面向：

| 项目 | 要求 |
| --- | --- |
| 操作系统 | Windows x86-64 |
| Minecraft | 26.2 |
| Mod Loader | Fabric Loader 0.19.3 或更高 |
| 依赖 | 对应 Minecraft 版本的 Fabric API |
| Java | 25 |
| 图形后端 | Vulkan |
| GPU | 支持 Vulkan KHR ray tracing pipeline 和 acceleration structure |

DLSS Ray Reconstruction 只在受支持的 NVIDIA RTX GPU 上可用；其他满足要求的 GPU 使用
NRD-FSR。玩家不需要安装 Vulkan SDK，发行 JAR 已包含运行所需的 Windows 原生库。

Prime 对 GPU、显存和 CPU 流送的要求明显高于原版。建议使用最新的稳定显卡驱动。

## 安装

1. 安装 Minecraft 26.2、Fabric Loader 和 Fabric API。
2. 从 [GitHub Releases](https://github.com/bWFuanVzYWth/prime/releases) 下载 Prime JAR。
3. 将 Prime 和 Fabric API 的 JAR 放入游戏实例的 `mods` 目录。
4. 启动游戏，在图形设置中选择 Vulkan 后端并重启。
5. 在“视频设置 → Prime 路径追踪”中启用 Prime。

如果 Vulkan 或所需光追功能不可用，Prime 会停止接管并保留原版世界渲染。

### 首次启动

首次启用 Prime 或更新驱动、Prime shader 后，显卡驱动需要编译光追管线。这可能持续数分钟，
期间游戏窗口可能暂时无响应。后续启动通常会复用驱动缓存。不要在编译过程中强制结束游戏，
除非日志已经明确报告失败。

## 常用设置

- **Prime 路径追踪**：在原版和 Prime 之间切换。关闭后不会继续运行或保留 Prime 的
  路径追踪、降噪和超分资源。
- **降噪/超分后处理**：选择 DLSS RR、NRD-FSR 或无后处理。无后处理会直接显示每帧
  1 spp 噪声，只适合诊断。
- **重建质量**：控制内部渲染分辨率。建议从“质量”开始；帧率不足时再选择“均衡”或
  “性能”。
- **太阳、星空和方块光源亮度**：使用 EV 调整，每增加 1 EV 亮度翻倍。
- **缺省材质粗糙度**：只影响没有提供 PBR 粗糙度的材质，默认值为 0.90。

### 参考累积截图

按 `Ctrl+Alt+F2` 进入或退出参考累积截图，按 `Esc` 退出。该模式会冻结相机、场景、太阳和
方块动画，在窗口原生分辨率持续累积；画面会从噪点逐渐变干净。它不使用 DLSS、NRD 或
FSR，也不影响普通 `F2` 截图。这里的“参考”表示直接累积 Prime 当前有限反弹材质模型的
原始估计，不表示材质在现实物理上正确。

## 材质包与兼容性

声明 `format=lab-pbr/1.3` 的资源包可以提供粗糙度、介电 F0、金属、次表面权重和发光强度。
Prime 会读取切线空间法线，但当前仍使用几何法线；AO、高度和孔隙率尚未参与着色。

Prime 使用 Minecraft 的模型和资源系统构建光追场景，因此大多数普通方块和资源包可以直接
工作。依赖特殊实时渲染、非标准模型回调或自定义 GPU 管线的 mod 可能不兼容。

当前重要限制：

- 相机首透明面固定混合确定性折射与 Fresnel 反射，之后透明面固定选择折射；玻璃每个
  穿过的表面按纹理 RGB 与 alpha 滤色，厚玻璃通常滤色两次；NEE 阴影不折射，只累计
  透明滤光；
- 只有水进入体积栈并按实际水中距离吸收；水边界需有正确法线和可解释的进出拓扑，
  最多可靠嵌套两个非空气水体区域；
- 部分资源包的红石火把头部可能被错误识别为不透明几何；
- 高视距持续加载复杂地形时仍可能出现较高 Java 堆分配和 GC 停顿；
- 洞穴视距雾漏光仍在调查。

完整列表和临时规避方法见[已知问题](docs/FIXME.md)。

## 报告问题

请在 [GitHub Issues](https://github.com/bWFuanVzYWth/prime/issues) 提供：

- Prime、Minecraft、Fabric Loader 和 Fabric API 版本；
- GPU 型号与驱动版本；
- 使用的资源包、相关 mod 和后处理模式；
- 可重复的操作步骤；
- 导出的完整日志；画面问题最好附截图或短视频。

若问题只在 Prime 中出现，请同时确认切换回原版渲染后是否消失。

## 开发者文档

- [纯函数式架构](docs/纯函数式架构.md)
- [渲染实现](docs/渲染实现.md)
- [透明渲染与实时重建](docs/透明渲染与实时重建.md)
- [构建与验证](docs/构建与验证.md)
- [着色器属性测试与数值诊断](docs/着色器属性测试与数值诊断架构.md)
- [BSDF 透射折射率平方缺失调查](docs/BSDF透射折射率平方缺失调查报告.md)
- [未实施的着色器性能方案](docs/着色器性能优化-未实施的方差与偏差方案.md)
- [TODO](docs/TODO.md) 与 [FIXME](docs/FIXME.md)

## 快速构建

开发环境需要 JDK 25、Vulkan SDK 1.4.350，以及 SDK 中的 `glslangValidator` 和
`spirv-val`：

```powershell
.\gradlew.bat clean build
```

运行开发客户端：

```powershell
.\gradlew.bat runClient
```

完整环境、构建选项和测试命令见[构建与验证](docs/构建与验证.md)。

## 许可与归属

Prime 自有代码使用 [MIT License](LICENSE)。NRD、DLSS、FidelityFX 和 RoboCute 相关组件
保留各自许可；完整文本见 `THIRD_PARTY_LICENSES`。

夜空资源来自 [NASA SVS Deep Star Maps 2020](https://svs.gsfc.nasa.gov/4851/)：
NASA/Goddard Space Flight Center Scientific Visualization Studio. Gaia DR2:
[ESA/Gaia/DPAC](https://gea.esac.esa.int/archive/documentation/GDR2/Miscellaneous/sec_credit_and_citation_instructions/)。
星座图形基于 Alan MacRobert 为 IAU 制作并发表于 *Sky and Telescope* 的版本
（Roger Sinnott 与 Rick Fienberg）。完整归属与无损重打包说明见
`THIRD_PARTY_LICENSES/NASA-DEEP-STAR-MAPS-2020-NOTICE.md`。
