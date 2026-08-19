# LitePBR 轻量材质模型

`shaders/bsdf/lite/bsdf.slang` 是轻量实时渲染器专用的低阶材质模型。它复用 Prime 已验证的
材质翻译 ABI、介质栈和适配器边界，但不符合 OpenPBR，也不作为实时完整模式或离线参考渲染器
的结果来源。`shaders/bsdf/compact` 仍是仓库内唯一的 OpenPBR 实现。

## 模型边界

LitePBR 以可解释的散射阶数截断和方向能量闭合代替经验画面拟合：

- dielectric opaque：single-scatter anisotropic GGX 界面，其缺失能量压回同一 GGX 瓣；基底使用
  粗糙界面的入射方向剩余能量；
- conductor：single-scatter anisotropic GGX 与 generalized Schlick/F82；
- thick transmission：Walter GGX 反射/折射、相关 Smith masking-shadowing、Snell/TIR 和
  Beer-Lambert 介质吸收；
- thin-wall：保留两界面 Fresnel/吸收的解析级数，但用轻量 Fresnel 和 single-scatter GGX；
- subsurface：保留当前余弦双半球薄层模型和各向异性前后概率；
- foliage：低阶 opaque 基底与 15% 双面 Lambert 漫透射薄层。

介质基底的连续部分为：

```text
R = directional_energy(single_scatter_GGX) * scalar_closure
f = scalar_closure * f_single_scatter_GGX
  + (1 - R) baseColor / pi
```

方向能量由无纹理的 GGX/Schlick 解析拟合得到。闭合只调整已有反射瓣的标量能量，不恢复
多次散射的低频角分布，也不增加 lobe 或采样事件。高粗糙度材质仍会与完整 OpenPBR 有差异，
但粗糙介质不再由宏观掠角 Fresnel 重复压暗。

该层叠是以当前路径入射方向为条件的能量混合，不是对称的双界面 BSDF；因此 opaque
dielectric 不声明反射互易性。这个取舍避免为另一方向再次求方向反照率，并保持 sample、PDF、
事件类型和降噪分类不变。

## 采样和成本

各 lobe 的 proposal 权重来自当前入射方向闭合能量、透射率和颜色平均能量。选中分量的响应与离散
选择概率成对进入 throughput/PDF，保持 LitePBR 自身的估计无偏。漫反射使用余弦半球，GGX
使用可见法线采样。

轻量程序不导入、声明或采样 OpenPBR transmission directional-energy 3D texture，并删除：

- table-based dielectric 和 conductor multiple-scattering compensation；
- directional-albedo LUT 坐标与校正；
- beta/gamma 形式的特殊粗糙透射遮蔽；
- OpenPBR EON 的零粗糙度随机数映射；
- foliage 初始化中的完整 transmission closure 构造。

`TraceBackend` 目前仍为可热切换的完整渲染器拥有该 LUT 和共享 descriptor 生命周期；取消这项
全局常驻资源属于后续资源布局优化，不影响 Lite shader 的执行闭包。

## 接入约束

所有 `realtime_wavefront_lightweight_*` raygen 在包含共享积分器前定义
`PRIME_USE_LITE_BSDF`。共享 adapter 随后只替换 BSDF 数学后端，保持以下边界不变：

- 材质和相邻介质翻译；
- 首透明面条件反射/透射双分支；
- 后续透明折射链策略；
- volume stack、eta、ray distance 和 Beer-Lambert 状态；
- BSDF event、降噪 albedo 和稳定 anchor ABI。

完整 realtime、offline 和测试参考入口不定义该宏，继续静态闭合到
`shaders/bsdf/compact`。

`LiteBsdfGpuTest` 扫描 opaque dielectric、conductor、subsurface、foliage、thick
transmission 和 thin-wall 六类拓扑的有限性、非负性、能量范围、导体反射互易性、介质基底
方向闭合、事件半球、sample/evaluate 支持和介质栈状态。`TracePipelinesContractTest` 从编译后的 SPIR-V 验证所有
轻量 raygen 不声明 OpenPBR energy binding，而完整 shade 程序仍声明该资源。
