# OpenPBR 紧凑模块

Prime 以 OpenPBR Surface 1.1.1 为材质语义标准，以 RoboCute
`0d982c77b3fd26c2c5a3c0852be3bd05e5860bd8` 和锁定的 author overlay 为第三方差分参考。
`shaders/bsdf/compact` 是新的生产目标；旧 `shaders/bsdf/core/robocute_*` 仅在迁移期提供
差分 oracle。

## 等价性契约

紧凑实现只允许浮点求值顺序、公共子表达式复用和编译器降级造成的舍入差异。事件类型、
delta/solid-angle measure、采样分布、PDF、eta 和介质状态转换必须精确保持；随机数到事件的
映射也必须保持，唯一例外是能量权重的允许舍入误差所形成的零测度比较边界。拓扑选择只能使用
精确的零/一权重、thin-walled 标志和离散材质类型，不使用 epsilon 裁剪有效 lobe。

每个紧凑拓扑必须同时实现 evaluate、sample、PDF 和 directional energy。支持判断是接口契约，
未满足判断的材质必须继续走已验证的回退，不能进入紧凑状态后静默降级。

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

## 切换策略

opaque、dielectric transmission 和 foliage 生产入口均已切换到紧凑状态。生产依赖图中不再可达
`PrimeRcState`、`prime_bsdf_specializations`、`robocute_openpbr` 或 `robocute_closures`；旧组合树
只由差分测试显式导入。`robocute_common/fresnel/microfacet` 中的低层数学原语暂时仍由生产使用，
将在后续批次迁入 compact 后再把 core 完全降为第三方参考。

以相同默认 `-g1` 构建参数比较，最大的 transparent-shade SPIR-V 从迁移前 1,330,220 字节降至
1,303,636 字节，减少 26,584 字节（2.00%）；函数体语义指令从 60,972 降至 59,775（1.96%），
条件分支从 3,892 降至 3,865，Phi 从 4,419 增至 4,436。相对于只迁移 opaque 的首批产物，完整
transmission/foliage 精确特化使该最大着色器增加 18,412 字节和 755 条语义指令；它仍小于迁移前
产物，但这是后续需要以寄存器、占用率和指令测量继续收口的运行性能信号。`-g0` probe 只用于
指令审查，不能与默认生产产物直接比较体积。

同机以四个 Slang 进程执行 51 个生产单元的
`compileSlangShaders --no-build-cache --rerun-tasks`，改动前隔离副本为 51 秒，只迁移 opaque 时为
43 秒，本批为 36.8 秒；相对迁移前单次观测缩短约 27.8%，相对首批再缩短约 14.4%。该数字用于
确认优化器负担方向，不替代多轮基准。

后续迁移和收口顺序：

1. 把 common/fresnel/microfacet 中仍被生产调用的低层 OpenPBR 原语迁到 compact；
2. 收缩 `PrimeRcMaterial`/volume ABI，移除只为参考组合树存在的字段；
3. 用 Nsight 的寄存器、occupancy 和 SASS 指令确认 transmission/foliage 特化的运行成本；
4. 在不改变公式、分布或有效 lobe 的前提下合并重复特化和公共子表达式。

完整 OpenPBR 的 coat、fuzz 和 thin-film 随后作为精确拓扑补齐；RoboCute diffraction 与 coat
roughening 是扩展状态，不混入 OpenPBR 标准核心。

## 测试

`CompactOpenPbrOpaqueGpuTest` 在 Vulkan 上执行 49,152 个用例。32,768 个端点用例扫描
dielectric、conductor、厚表面 subsurface 和 thin-wall subsurface，每个用例同时运行参考和紧凑
路径，并比较 eval、diffuse/specular 分量、PDF、采样事件、方向、directional energy、ray
distance 和 volume stack。另有 16,384 个分数 subsurface 用例覆盖零附近、0.25、0.5、0.75 和
一附近的权重、厚/薄表面、随机数边界、掠射角、IOR、各向异性和粗糙度，并检查分量求和、PDF、
事件、能量、有效结果的有限性与非负性；其 eval、diffuse 分量和 directional energy 还必须等于
两个精确端点按 subsurface 权重的线性组合，specular 分量必须与两个端点一致。通用 RoboCute
组合树继续由原有 closure 属性测试独立覆盖，避免把两个大型实现塞进同一 Slang 优化单元。

`CompactOpenPbrTransmissionGpuTest` 执行 36,864 个参考差分用例，覆盖厚介质进入/退出、thin-wall、
三组 sampling flags、smooth/rough 与 index-matched 边界，并比较状态、eval、PDF、事件、方向、
throughput、eta、directional energy、volume stack 和退出时的 ray distance。

`CompactOpenPbrFoliageGpuTest` 执行 12,288 个参考差分用例，覆盖 dielectric、分数 subsurface 和
conductor 三种拓扑，比较总 eval、diffuse/specular 分量、PDF、采样、directional energy 和紧凑
组合状态。若随机数落在参考与紧凑权重因允许的舍入差异形成的 `1e-6` 零测度边界带内，测试仍
比较权重、eval、PDF 和能量，但不要求两个浮点实现选择同一离散 lobe；其余用例要求采样事件、
方向和响应一致。

被拒绝且事件为 `NONE` 的 proposal payload 沿用 adapter 现有契约：payload 未定义且不会被消费，
测试不以其中的 NaN/Inf 判定失败；任何有效事件的 payload 仍必须完整通过数值检查。
