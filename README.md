# Prime

Prime 是 Minecraft 26.2 的客户端路径追踪渲染 Mod，使用 Vulkan KHR 硬件光线追踪。
它提供实时 Wavefront 路径积分、时空重建，以及独立的原生分辨率累积截图模式。

> Prime 仍处于早期开发阶段。画质、性能和兼容性会继续变化，不建议在没有备份的情况下
> 将它用于重要存档。

[下载发行版](https://github.com/bWFuanVzYWth/prime/releases) ·
[报告问题](https://github.com/bWFuanVzYWth/prime/issues) ·
[已知问题](docs/FIXME.md)

## 主要功能

- 实时 1 spp Wavefront 积分器，提供长尾路径、灯光树，以及太阳和局部发光面的完整 NEE；
- 光谱大气、太阳、天空、Gaia 全天星空，以及由原版发光等级或 LabPBR 发光数据生成的
  方块面光源；
- 支持 DLSS Ray Reconstruction，并在不可用或初始化失败时回退到 NRD + FSR；
- 全分辨率 HDR 自动曝光，以及不重置实时历史或截图累积的最终曝光调整；
- 体积吸收玻璃与水体、金属和粗糙表面；透明阴影沿直线累计介质吸收；
- 支持部分 LabPBR 1.3 材质，以及可选的实验性纹理体素 relief；
- 冻结场景并在原生分辨率累积原始路径样本的参考截图模式；
- 内建重建、数值、场景和 GPU 资源诊断；
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
- **重建质量**：控制内部渲染分辨率，默认“性能”。NRD-FSR 使用固定比例，DLSS RR 由
  NVIDIA NGX 查询对应档位的实际尺寸；禁用后处理时固定为原生分辨率。
- **观察纬度与季节**：共同控制真实太阳轨迹和星图旋转。默认北纬 30°、春分；季节是
  手动固定的全年位置，不随 Minecraft 天数自动推进。
- **太阳、星空和方块光源亮度**：使用 EV 调整，每增加 1 EV 亮度翻倍。
- **最终曝光度**：在自动曝光结果上追加纯显示 EV 偏移；截图累积期间也可即时调整。
- **缺省材质粗糙度**：只影响没有提供 PBR 粗糙度的材质，默认值为 0.90。
- **无缝玻璃**：默认开启，使普通玻璃不显示边框、染色玻璃不显示磨砂区域，体积颜色与
  吸收保持不变；关闭后恢复材质纹理中的装饰分区。
- **纹理体素颗粒表面**：在玩家附近把合格方块面替换为按基础纹理亮度或 LabPBR 高度生成的
  像素 relief；这是实验性功能，会增加 CPU 建网和 BLAS/TLAS 开销。

重建质量的默认值设为“性能”，只是为了让较低端但满足功能要求的设备在首次启动或恢复
默认值后不容易因内部像素数和显存开销而严重卡顿，并不代表对所有设备推荐这个具体档位。
请根据 GPU、输出分辨率、目标帧率、场景和材质包自行调整重建质量。

### 参考累积截图

按 `Ctrl+Alt+F2` 进入或退出参考累积截图，按 `Esc` 退出。该模式会冻结相机、场景、天文
状态和方块动画，在窗口原生分辨率持续累积；画面会从噪点逐渐变干净。它不使用 DLSS、NRD 或
FSR，也不影响普通 `F2` 截图。这里的“参考”表示直接累积 Prime 当前有限反弹材质模型的
原始估计，不表示材质在现实物理上正确。调整最终曝光只重新生成显示结果，不丢弃已经
累积的样本。

## 材质包与兼容性

按 `format=lab-pbr/1.3` 声明的资源包可以提供粗糙度、介电 F0、金属、次表面权重和发光
强度。Prime 会读取切线空间法线数据，但 BSDF 与重建 guide 当前仍统一使用几何法线；法线
纹理 alpha 中的高度仅用于可选的纹理体素表面，AO 和孔隙率尚未参与着色。

Prime 使用 Minecraft 的模型和资源系统构建光追场景，因此大多数普通方块、物品展示框和
资源包可以直接工作。依赖特殊实时渲染、非标准模型回调或自定义 GPU 管线的 mod 可能不兼容。

当前重要限制：

- 静态二次幂 cutout 纹理在 256×256 以内使用匹配分辨率的二值 OMM；更高分辨率按已知上限
  近似，极细透明边界可能错误；
- 实时积分器在相机首透明面以 50/50 时空棋盘格选择一条条件反射或条件透射路径，后续
  透明面从完整闭包随机选择单分支。染色玻璃 alpha 不高于 0.5 的区域为光滑界面，高于
  0.5 的区域为粗糙界面，但体积吸收统一按 alpha 0.4 标定；普通玻璃是无色光滑主体与粗糙
  漫反射边框。粗糙度优先来自 LabPBR，否则使用缺省材质粗糙度设置；默认开启“无缝玻璃”，
  禁用普通玻璃边框与染色玻璃磨砂分类，吸收不变；
  实时重建 guide 由独立确定性透射/平面反射探针提供，不改变随机辐射；NEE 阴影不折射，
  只沿原连接线累计体积吸收；
- 实体玻璃与水都占用介质栈。透明边界需有
  正确法线和可解释的进出拓扑，最多可靠嵌套两个非空气透明区域；
- Minecraft 云、通用雾以及局部参与介质尚未作为完整体积传输求解；
- NRD-FSR 无法可靠降噪彩色玻璃后的传输信号，当前接受残余噪点或时间性不稳定；
- 封闭室内在特定观察角度可能因体积光精度不足出现同心圆状亮带和漏光；
- 动态发光实体不进入灯光树，只能在路径实际命中时贡献；
- 部分资源包的红石火把头部可能被错误识别为不透明几何；
- 纹理体素 relief 可能遮住发光地衣、火焰等紧贴宿主表面的独立内容；
- 高视距持续加载复杂地形时仍可能出现较高 Java 堆分配和 GC 停顿；
- 史莱姆等需要半透明、内部散射或柔软质感的实体材质尚未完整表达；
- 信标光柱仍可能错误遮光；两层以上透明介质嵌套、非流形边界和缺失界面的吸收顺序不受
  保证。

完整列表和调查方向见[已知问题](docs/FIXME.md)。

## 报告问题

请在 [GitHub Issues](https://github.com/bWFuanVzYWth/prime/issues) 提供：

- Prime、Minecraft、Fabric Loader 和 Fabric API 版本；
- GPU 型号与驱动版本；
- 使用的资源包、相关 mod 和后处理模式；
- 可重复的操作步骤；
- 导出的完整日志；画面问题最好附截图或短视频。

若问题只在 Prime 中出现，请同时确认切换回原版渲染后是否消失。

## 开发者文档

- [架构与数据流](docs/纯函数式架构.md)
- [区块簇场景翻译架构](docs/区块簇场景翻译架构.md)
- [渲染实现](docs/渲染实现.md)
- [透明渲染与实时重建](docs/透明渲染与实时重建.md)
- [构建与验证](docs/构建与验证.md)
- [着色器属性测试与数值诊断](docs/着色器属性测试与数值诊断架构.md)
- [RoboCute BSDF 参考与透射契约](docs/BSDF透射折射率平方缺失调查报告.md)
- [TODO](docs/TODO.md) 与 [FIXME](docs/FIXME.md)

## 快速构建

开发环境需要 JDK 25、Vulkan SDK 1.4.350，以及 SDK 中的 `glslangValidator` 和
`spirv-val`：

```powershell
.\gradlew.bat build
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
