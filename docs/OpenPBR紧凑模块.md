# OpenPBR 紧凑模块

Prime 以 OpenPBR Surface 1.1.1 为材质语义标准，以 RoboCute
`0d982c77b3fd26c2c5a3c0852be3bd05e5860bd8` 和锁定的 author overlay 为第三方行为参考。
`shaders/bsdf/compact` 是仓库内唯一实现；本地 RoboCute Slang port 已删除，参考项目不进入
生产或测试编译闭包。

## 等价性契约

紧凑实现只允许浮点求值顺序、公共子表达式复用和编译器降级造成的舍入差异。事件类型、
delta/solid-angle measure、采样分布、PDF、eta 和介质状态转换必须精确保持；随机数到事件的
映射也必须保持，唯一例外是能量权重的允许舍入误差所形成的零测度比较边界。拓扑选择只能使用
精确的零/一权重、thin-walled 标志和离散材质类型，不使用 epsilon 裁剪有效 lobe。

每个紧凑拓扑必须同时实现 evaluate、sample、PDF 和 directional energy。支持判断是接口契约，
当前材质翻译只允许构造已覆盖拓扑；支持判断用于验证这一边界。新增 OpenPBR 能力必须先补齐
精确拓扑和行为测试，不能回退到参考库或在紧凑状态中静默近似。

## 当前覆盖

`bsdf/compact/openpbr_opaque.slang` 覆盖 Prime 当前 opaque 翻译可产生的四类精确
OpenPBR 退化拓扑：

- dielectric base：Lambert base 与 dielectric specular 的能量感知层叠；
- conductor：GGX conductor 与 F82 tint；
- subsurface：权重精确为一的厚表面或 thin-wall subsurface 与 dielectric specular 的层叠；
- mixed subsurface：分数权重的 diffuse/subsurface 混合，再与 dielectric specular 层叠。

该子集要求 base/specular weight 为一、metalness 精确为零或一，且 transmission、coat、fuzz、
thin-film 和 diffraction 权重为零。当前材质翻译的 `diffuse_roughness` 精确为零，因此首批不携带
通用 EON 粗糙漫反射；分数 subsurface 路径仍保留 EON 在零粗糙度下的随机数到方向映射。
`primeCompactOpaqueSupports` 集中表达这些前置条件，并拒绝当前未实现的非零
`diffuse_roughness`。

旧 adapter 曾把所有非零 subsurface 权重送入“权重精确为一”的 RoboCute specialization，导致
分数权重被忽略。紧凑路径改为按精确端点和分数区间分派，恢复 OpenPBR 定义的
diffuse/subsurface 混合；这属于标准一致性修复，不是模型近似。

紧凑状态不保存完整材质、未激活的 transmission/coat/fuzz/thin-film/diffraction 状态或通用
OpenPBR 组合树；sampling flags 作为操作参数传入，分量求值不再通过复制整个状态切换 flags。

`bsdf/compact/openpbr_transmission.slang` 覆盖当前 dielectric transmission 的完整生产集合：厚介质
进入/退出、thin-wall、smooth/rough GGX、全闭包与条件 reflection/transmission 采样，以及相邻介质
和 air-gap 适配。状态只保留界面 ONB、相对/原始 IOR、两个实际使用的 microfacet、Fresnel、薄壁
tint、体积参数和多次散射补偿；sampling flags 不再驻留在状态中。dispersion 仍由支持判断明确拒绝。

`bsdf/compact/openpbr_foliage.slang` 覆盖当前固定薄壁 foliage 子集：dielectric diffuse/subsurface、
specular layer 和 15% colored thin-wall transmission，以及材质编码允许的 conductor 旁路。它复用
紧凑 opaque substrate，只增加 transmission microfacet、tint 和能量感知的 dielectric mix 状态。
普通 diffuse 也保留通用 OpenPBR EON 在零粗糙度时的随机数到方向映射。

`bsdf/compact/openpbr_material.slang` 机械承接默认 metallic 材质初始化和 outside-IOR 查询，使生产
适配器不再仅为这两个边界函数导入完整 OpenPBR 组合模块。

`bsdf/compact/openpbr_common.slang` 定义生产自有的 `PrimeOpenPbr*` 材质、采样、事件和两层体积栈
ABI，但不定义通用组合树或完整 BSDF state。`openpbr_fresnel.slang` 只保留当前拓扑可达的
dielectric、conductor 和 F82 tint 公式；紧凑 Fresnel state 不携带三个 thin-film 字段，因为支持
边界精确拒绝非零 thin-film。`openpbr_microfacet.slang` 只保留可达的 GGX 分布、采样、PDF、
directional energy 和反射基础操作，不包含依赖完整状态的通用折射入口。

## 切换策略

opaque、dielectric transmission 和 foliage 生产入口均已切换到紧凑状态。生产 ABI、Fresnel、
microfacet、材质初始化和体积栈全部由 compact 所有；仓库中不再保留第二套 RoboCute 组合树。
`verifyShaderIncludeGraph` 会拒绝恢复已删除的 `shaders/bsdf/core` 或历史 reference
specialization，并继续检查所有现存 Slang 依赖可解析且无环。

迁移评估以相同的 `-g1` 构建参数比较，最大的 transparent-shade SPIR-V 从迁移前 1,330,220 字节降至
1,303,804 字节，减少 26,416 字节（1.99%）；函数体语义指令从 60,972 降至 59,775（1.96%），
条件分支从 3,892 降至 3,865，Phi 从 4,419 增至 4,436。相对上一批产物，本次独立 ABI/数学模块
只增加 168 字节，语义指令、条件分支和 Phi 均不变，说明参考依赖移除没有扩大执行程序。
`-g0` probe 只用于指令审查，不能与默认生产产物直接比较体积。

同机以四个 Slang 进程执行 51 个生产单元的
`compileSlangShaders --no-build-cache --rerun-tasks`，用上一批提交 `87dfa25` 的隔离 worktree 做
配对复测：上一批两次为 52.8/52.1 秒，本批三次为 50.7/44.5/43.6 秒；中位数由约 52.5 秒降至
44.5 秒，约缩短 15.2%。该命令强制重新编译全部单元，但不清空操作系统文件缓存；数据用于确认
优化器负担方向，不替代更多轮次和机器的基准。

后续迁移和收口顺序：

1. 用 Nsight 的寄存器、occupancy 和 SASS 指令确认 transmission/foliage 特化的运行成本；
2. 在不改变公式、分布或有效 lobe 的前提下合并重复特化和公共子表达式；
3. 按实际产品需求补齐 coat、fuzz 和 thin-film 的精确紧凑拓扑及测试。

完整 OpenPBR 的 coat、fuzz 和 thin-film 随后作为精确拓扑补齐；RoboCute diffraction 与 coat
roughening 是扩展状态，不混入 OpenPBR 标准核心。

## 测试

`CompactOpenPbrOpaqueGpuTest` 在 Vulkan 上执行 49,152 个用例。32,768 个端点用例扫描
dielectric、conductor、厚表面 subsurface 和 thin-wall subsurface；另有 16,384 个分数
subsurface 用例覆盖零附近、0.25、0.5、0.75 和一附近的权重、厚/薄表面、随机数边界、掠射角、
IOR、各向异性和粗糙度。测试直接验证 eval 分量求和、PDF、事件、能量、有限性、非负性和
状态不变量；分数路径还验证端点线性组合性质，不再把第二套参考实现编进同一测试单元。

`CompactOpenPbrTransmissionGpuTest` 执行 36,864 个性质用例，覆盖厚介质进入/退出、thin-wall、
三组 sampling flags、smooth/rough 与 index-matched 边界，并验证状态、eval、PDF、有效事件、
方向、eta、directional energy、volume stack 和退出时的 ray distance。参考公式可能生成的
无效 proposal 按生产 adapter 的接受谓词拒绝，不消费 provisional event 或介质状态。

`CompactOpenPbrFoliageGpuTest` 执行 12,288 个性质用例，覆盖 dielectric、分数 subsurface 和
conductor 三种拓扑，验证总 eval 与 diffuse/specular 分量求和、PDF、采样事件、directional
energy 和紧凑组合状态。

`OpenPbrCoreGpuTest` 验证 common、Fresnel 和反射 microfacet 的恒等式、边界与互易性质；
`OpenPbrDistributionGpuTest` 以采样直方图对 PDF，并以 Monte Carlo 能量对独立求积；
`OpenPbrTransmissionSlabGpuTest` 验证两界面 Snell、TIR、互反 eta、介质栈和 ray distance。

被拒绝且事件为 `NONE` 的 proposal payload 沿用 adapter 现有契约：payload 未定义且不会被消费，
测试不以其中的 NaN/Inf 判定失败；任何有效事件的 payload 仍必须完整通过数值检查。
