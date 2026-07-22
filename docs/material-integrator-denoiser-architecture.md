# 材质、积分器与降噪器的一体化边界

本文记录 Prime 在固定 1 spp 实时路径追踪下的材质能力、无偏估计器边界和重建后端契约。
目标不是把渲染拆成互不理解的“积分—降噪—超分”黑盒，而是让积分器一次产出完整的物理估计与
可追溯的表面观测，再由各后端选择自己能正确解释的信息。

## 不变量

1. `PrimePathRadiance` 是完整 beauty estimator 的互斥分区。`diffuse + specular + stable +
   unshadowedSun * sunVisibility` 必须与未拆分的一条路径样本等价。
2. `PrimeDenoiserGuides` 只描述路径和主表面。Guide 可以使用额外射线或后端特有近似，但不得反馈到
   beauty estimator。
3. 参考累积、NRD-FSR 和 DLSS RR 都是 `Denoiser` 后端。参考累积是零滤波、零超分的特殊后端，
   它直接对完整路径估计取运行均值。
4. 实时输出允许有偏，因为时间重建本身就是有偏估计；偏差必须位于积分器之后并能按后端定位。
5. 1 spp 只意味着高方差，不等于有偏。概率式 lobe 选择、NEE/MIS 和带吞吐补偿的 Russian roulette
   在 PDF 与权重一致时仍然无偏。

## LabPBR 1.3 能力边界

LabPBR 是材质贴图的存储标准，不是完整的表面/体积散射模型。Prime 当前完整解码 `_n` 与 `_s`
八个通道，再把有明确物理闭包的部分翻译到 RoboCute BSDF。

| LabPBR 信息 | 当前状态 | Prime 中的物理含义 | 下一边界 |
|---|---|---|---|
| Tangent normal X/Y（Z 重建） | 已解码、未着色 | 仍使用几何/模型 shading normal | 建立稳定 tangent frame、mip 过滤、法线修正，并同步给 BSDF、NRD 与 RR |
| AO | 已解码、未使用 | 不把烘焙 AO 乘入路径吞吐，避免重复遮蔽 | 仅作为艺术/微观遮蔽模型的显式输入，不直接污染无偏可见性 |
| Height | 已解码、未使用 | 无位移或视差近似 | 几何位移、微网格或有明确定义的微表面模型 |
| Smoothness / roughness | 已生效 | RoboCute microfacet roughness；同值提交降噪器 | 加入法线贴图后联合过滤 roughness 与 normal variance |
| Dielectric F0 | 已生效 | 线性 F0，当前约束到材质适配器支持范围 | 可扩展到更一般 IOR/吸收模型 |
| Predefined/custom metals | 已生效 | 金属基底、导体反射能量及 NRD 材质分类 | 自定义金属仍受 RGB 工作空间而非光谱模型限制 |
| Porosity | 已解码、未使用 | 尚无雨湿/吸水状态 | 与天气、液膜、粗糙度和暗化模型一起实现 |
| SSS weight | 部分生效 | 仅 authored alpha-cutout foliage 的 thin-wall 近似 | 需要厚度/mean-free-path 后实现 BSSRDF 或体积 random walk |
| Emission | 已生效 | 纹素辐射亮度，进入灯光树、NEE 与 MIS | 继续完善绝对标定和动画发光采样 |
| Base color / opacity | 已生效 | 漫反射基底、coverage 或透射接口 | coverage 与真实介质继续严格分离 |

### 当前实际材质域

- 不透明介电材质、LabPBR 金属、粗糙/光滑 microfacet、发光表面；
- alpha cutout 与薄叶片透射；
- 玻璃、染色玻璃、水、薄壁透明模型；
- Fresnel 反射/折射、Beer–Lambert 吸收、两层嵌套介质栈；
- 天空、太阳、面积发光体和大气透视。

在 Prime/Minecraft 的产品范围内，补齐稳定法线贴图、体积散射和真实 SSS 后，已经能够覆盖绝大多数
预期材质意图。但这不是通用离线材质系统的终点：各向异性、clear coat、sheen/fuzz、薄膜干涉、
色散、毛发/纤维、异质体积和真实位移仍需要新的显式参数与闭包，不能从 LabPBR 1.3 八通道可靠反推。

## 积分器结构

### 物理估计

`integrator.glsl` 只负责同一条完整路径上的：

- 介质段吸收；
- 表面发光与环境命中；
- 太阳/面积光 NEE；
- BSDF 采样、正反向 PDF 和 power-heuristic MIS；
- 跨反弹体积栈；
- Russian roulette 与路径终止；
- 对完整 radiance sample 的互斥 signal 分类。

`primeResolveIntegrationRadiance` 是唯一 beauty 合成公式，截图后端调用该函数，避免消费者遗漏太阳
可见性或 stable signal。

### 重建观测

同一次路径遍历记录 `PrimeDenoiserGuides`：主表面位置/距离、法线、roughness、材质分类、漫反射与
视角相关镜面 albedo、首个后续 diffuse/specular hit distance，以及太阳 penumbra。`PrimeDenoiserState`
封装 delta chain 上的 albedo 传播和首次 non-delta 分类，不包含任何具体 Vulkan image 或 SDK 资源名。

当前实时 raygen 不追踪后端专用的辅助输运路径。RR 的 `Color Before Transparency` 输入留空，玻璃和
水与其他材质一样只积分一条完整 beauty path；specular hit distance 也只来自该路径已经采样的首次后续
命中。这保持了固定 1 spp 的单路径成本，代价是 RR 不再获得首层透明界面的显式分界信号。

### 偏差清单

| 项目 | 类型 | 当前处理 |
|---|---|---|
| BSDF PDF 使用 `max(pdf, 1e-4)`，透明分支拒绝 `p <= 1e-7` | 有限精度暗偏 | 必须保留；防止倒数权重溢出为 Inf 后在零吞吐/可见性处形成 `0*Inf=NaN` |
| MIS 的 `1/pdf` 限制为 `1e30` | 有限精度暗偏 | 必须保留；稳定代数避免中间溢出，上限继续保证最终 MIS 因子有限 |
| BSDF evaluation 忽略 `cos(theta) <= 1e-7` | 有限精度掠射角暗偏 | 必须保留；所有相关闭包需要除以 cosine，阈值阻止 Inf/NaN 污染路径 |
| 256 bounce 上限 | 异常路径截断 | 保留并作为工程无偏定义中的显式安全边界 |
| ray origin offset、有限 `tMax` | 数值/场景范围近似 | 保留；物理 shading point 与 traversal origin 已分离 |
| NaN/Inf/越界浮点样本拒绝 | 有限精度安全边界 | 参考累积不以黑色替代，直接保留旧均值与计数 |
| 材质参数 clamp/缺省值 | 场景模型近似 | 不属于相对当前场景模型的 Monte Carlo 偏差，但必须由材质适配层声明 |
| NRD/RR FP16 65504 边界 | 实时重建输入偏差 | 只存在于实时后端的可表示范围边界；截图 RGBA32F 路径不使用 |
| NRD anti-firefly、history fix、prepass | 空间/时间滤波偏差 | 后端有意行为，不反馈积分器 |
| FSR 与 DLSS RR 时间超分/历史锁定 | 重建偏差 | 后端有意行为，可通过参考累积对照 |
| RR 透明 Guide | 未提交 | 固定 1 spp 只积分 beauty path，不追加透明辅助路径 |

## 三个 Denoiser 后端

### Reference accumulation

- 输入：完整 `PrimePathRadiance` 的合成结果；
- 处理：原生分辨率 RGBA32F 运行均值；
- 不使用：Guide、实时 jitter history、NRD、FSR、RR、firefly clamp；
- 作用：质量上限、偏差审计基准、所有实时后端的回归 oracle。

### NRD-FSR

当前使用 `REBLUR_DIFFUSE_SPECULAR_SH + SIGMA_SHADOW + FSR 3.1.4`：

- 已提供 non-jittered 2.5D motion、world normal、linear roughness、view-Z；
- diffuse/specular 按方向能量拆分并 demodulate，SH1 保存主面积光与延续路径的辐射加权一阶方向矩，分别附带 normalized hit distance；
- 太阳 radiance 与 visibility/penumbra 分离，SIGMA 只过滤 shadow signal；
- R10G10B10A2 的 A2 现在区分普通介电、金属、透明接口和 foliage，避免跨材质历史混合；
- 概率 lobe 采样使用 5×5 hit-distance reconstruction 和 30/50 像素 prepass。

仍未使用的主要能力：

1. **Diffuse/specular confidence**：需要在当前帧低分辨率重评上一帧照明并模糊 confidence，不能用常量
   假装。它对动画灯光、可见性变化和高频材质最有价值。
2. **Primary Surface Replacement**：纯镜面/delta chain 可把 first non-delta virtual surface 作为 NRD
   主表面。当前只传播 albedo，primary depth/normal 仍是首个可见接口；镜面房间和多层折射是边界。
3. **Disocclusion threshold mix**：适合曲面镜、细叶片和未来法线贴图造成的高曲率区域。
4. **Translucent shadow**：当前太阳 visibility 是二值遮挡；有色半透明阴影需要 SIGMA translucent signal
   与物理 shadow transmittance 一起设计。

REBLUR 仍比 RELAX 更适合当前“原始 1 spp、单路径、概率 lobe”信号。若未来引入 RTXDI/ReSTIR 或显著
更干净的局部估计，再重新比较 RELAX，不能只按名称替换。

### DLSS Ray Reconstruction

当前提交给 NGX 的实际图像包括：

- 完整 noisy linear Rec.2020 HDR color；
- current-to-previous、non-jittered、低分辨率 motion；
- linear view-Z、world shading normal、linear roughness；
- diffuse albedo、视角相关 specular albedo；
- world-space specular hit distance 与当前/上一帧矩阵。

天空的 diffuse/specular albedo 使用 SDK 推荐的中性 `0.5`，透明首界面的 diffuse albedo 为零。当前
`pInColorBeforeTransparency` 为 `nullptr`，不分配或生成对应图像，也不为玻璃/水追加第二条路径。

暂不接入：

- Transparency Layer：只参与超分，不参与 RR 降噪，不适合玻璃/水的完整路径结果；
- SSS Guide：它描述屏幕空间 SSS blur 的 before/after 差值。未来若 SSS 由无偏 BSSRDF/random walk
  直接积分，它本身就是 noisy diffuse transport，不应为了使用该 Guide 再引入有偏屏幕空间 blur；
- DoF Guide、动态分辨率、自动曝光和 sharpening；
- 独立 dynamic-object motion：加入实体路径追踪时必须同时补齐，静态地形 motion 不能覆盖该语义。

RR 对采样独立性比 NRD 更敏感。公共采样器继续使用逐像素、逐帧扰动的低差异样本；不得为了 NRD
单独加入屏幕共享 hash、checkerboard 或固定蓝噪声图案。初始相机 jitter 是允许的例外，NGX 单独接收
其负值，而 motion 本身不包含 jitter。

## 演进顺序

1. **法线闭环**：构建可靠 tangent frame，做 mip/roughness 联合过滤，并保证 BSDF、NRD、RR 读取同一
   world shading normal。这是当前 LabPBR 信息利用率最高的缺口。
2. **运动闭环**：实体进入路径追踪前，先定义 primary/virtual surface 的 object motion；否则任何新增材质
   细节都会被错误历史掩盖。
3. **PSR 与 confidence 原型**：用镜面房间、玻璃后运动体和动画发光材质对比 NRD 与参考累积，分别测量
   额外射线、带宽和历史收益，再决定是否进入默认路径。
4. **体积散射**：把 homogeneous/heterogeneous medium sampling、phase function、NEE 和 transmittance
   放进物理积分器；降噪器只接收其结果与可定义的 Guide。
5. **真实 SSS**：优先 BSSRDF/random walk 的无偏版本；若另设实时 screen-space SSS，必须作为独立的
   有偏 Denoiser 后端能力，并启用 RR SSS Guide。
6. **SH/各向异性扩展**：当前 SH1 已覆盖单次主面积光与延续路径；正常贴图和纤维材质到来后，再评估更完整的方向分布与新的 LabPBR 外部材质参数。

每一项新能力都应同时回答三个问题：它改变了场景模型还是估计器；它需要哪些可从同一路径无偏观测的
Guide；参考累积如何证明实时后端的变化只发生在预期的有偏重建边界。
