# HDR 输出

Prime 的 HDR 选项只在当前窗口所在屏幕同时满足以下条件时可用：Windows 已为该屏幕启用
HDR，DXGI 能报告有效的峰值亮度与 SDR 白电平，并且 Vulkan surface 提供
`R16G16B16A16_SFLOAT + EXTENDED_SRGB_LINEAR_EXT`。窗口或交换链重建时会重新检测；任一
条件不满足都会保留 SDR 交换链并禁用选项。

## 显示变换契约

AgX-HSV 来自 `C:\WorkSpace\drt` 的
`fa282723f5e1536b37217c10c74437fad054c87c`。Prime 保留自身的线性 Rec.2020 输入边界，
因此不会移植参考程序面向 ACES AP0 图像的输入矩阵。SDR 使用固定的 AgX-HSV toe、pivot、
色域压缩和 HSV 色相保持；HDR 只依据当前屏幕的线性亮度余量延伸同一 shoulder。Windows
查询保留峰值亮度 `P` 与系统 SDR 白电平 `W_system` 的绝对 nit 值，不能只保留两者比值。

“HDR 参考白”以 nit 标定 HDR 模式下的 SDR 白：配置值 `0` 表示自动使用 `W_system`，手动
值可从 1 nit 调到当前屏幕报告的 HDR 峰值；跨屏幕后若旧配置高于新屏峰值，运行时只在该屏
钳制到峰值，不改写持久配置。显示变换使用 `P / W` 推导 HDR shoulder，场景线性 18% 灰仍
固定映射为参考白的 18%，因此该选项不移动 AgX pivot 或 toe。运行时曲线系数使用 double
推导后再提交为 float，并精确通过所请求的输出峰值。

显示变换同时生成两个结果：

- RGBA8 中保留 SDR AgX-HSV 基线，供截图、原版 UI 和不支持 HDR 的交换链使用；
- RGBA16F 中保留 display-encoded extended-sRGB HDR 结果。

HDR 交换链最终接收线性 scRGB，scRGB 的 `1.0` 按 Windows 契约表示 80 nit，而不是当前
SDR 参考白。最终合成阶段先对 HDR 世界和原版 RGBA8 UI 分别执行扩展 sRGB EOTF，再在线性
域合成，并把完整结果乘以 `W / 80`。因此世界主体、原版 UI 和 SDR 白使用同一物理亮度标定，
而 HDR 峰值成为 `P / 80`。HDR 模式下世界基线的 alpha 固定为零，原版 alpha 混合由此累积
准确的 UI coverage；这避免把整条 Minecraft UI 管线和资源格式提升为浮点格式。全屏调试
视图写入 alpha=1，仍使用相同的参考白标定。该换算依据 Microsoft 的
[HDR 与 WCG DirectX 文档](https://learn.microsoft.com/windows/win32/direct3darticles/high-dynamic-range)
以及 [`DISPLAYCONFIG_SDR_WHITE_LEVEL`](https://learn.microsoft.com/windows/win32/api/wingdi/ns-wingdi-displayconfig_sdr_white_level)
契约。

## 不变量

- 不得让 RGBA8 中间结果成为 HDR 世界颜色的来源，高于 1.0 的信息会在此处不可逆丢失。
- 不得把 display-encoded AgX-HSV 输出直接提交给线性 scRGB 交换链。
- 不得在编码域把 HDR 世界与 UI 直接相加或插值。
- 不得把 scRGB `1.0` 当成可变的 SDR 参考白；绝对输出必须统一乘以 `W / 80`。
- 参考白只改变物理亮度标定和 `P / W` 高光余量，不得移动 SDR AgX pivot、toe 或场景曝光。
- 描述符所引用的同尺寸图像发生重建时，必须先结束旧的 GPU 使用；正常稳定帧不得为此等待。
- 屏幕能力或 Windows HDR 状态未知时必须回退 SDR，不能猜测峰值亮度。
