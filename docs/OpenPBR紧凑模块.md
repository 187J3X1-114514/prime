# OpenPBR 紧凑模块

Prime 以 OpenPBR Surface 1.1.1 为材质语义标准，以 RoboCute
`0d982c77b3fd26c2c5a3c0852be3bd05e5860bd8` 和锁定的 author overlay 为第三方差分参考。
`shaders/bsdf/compact` 是新的生产目标；旧 `shaders/bsdf/core/robocute_*` 仅在迁移期提供
差分 oracle。

## 等价性契约

紧凑实现只允许浮点求值顺序、公共子表达式复用和编译器降级造成的舍入差异。事件类型、
delta/solid-angle measure、采样分布、随机数到事件的映射、PDF、eta 和介质状态转换必须精确
保持。拓扑选择只能使用精确的零/一权重、thin-walled 标志和离散材质类型，不使用 epsilon
裁剪有效 lobe。

每个紧凑拓扑必须同时实现 evaluate、sample、PDF 和 directional energy。支持判断是接口契约，
未满足判断的材质必须继续走已验证的回退，不能进入紧凑状态后静默降级。

## 当前覆盖

首个模块 `bsdf/compact/openpbr_opaque.slang` 覆盖 Prime 当前 opaque 翻译可产生的四类精确
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

## 切换策略

opaque 生产入口已经切换到紧凑状态；旧 transmission/foliage 继续回退。以相同的默认 `-g1`
构建参数比较，最大的 transparent-shade SPIR-V 从 1,330,220 字节降至 1,285,224 字节，减少
44,996 字节（3.38%）；函数体语义指令从 60,972 降至 59,020（3.20%），条件分支从 3,892
降至 3,829，Phi 从 4,419 降至 4,305。`-g0` probe 只用于指令审查，不能与默认生产产物直接
比较体积。

同机以四个 Slang 进程执行 51 个生产单元的
`compileSlangShaders --no-build-cache --rerun-tasks`，改动前隔离副本为 51 秒，紧凑实现为 43 秒，
单次观测缩短约 15.7%。该数字用于确认优化器负担方向，不替代多轮基准；剩余 core 路径迁移后
再做稳定测量。

后续按当前多材质入口的剩余集合继续迁移：

1. thick/thin-wall dielectric transmission；
2. foliage 使用的 mixed-diffuse/specular/thin-wall-transmission 组合；
3. volume stack、进入/退出材质帧和相邻介质适配；
4. 移除生产入口对旧 core 的最后可达引用后，再测量完整冷编译和运行性能。

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

被拒绝且事件为 `NONE` 的 proposal payload 沿用 adapter 现有契约：payload 未定义且不会被消费，
测试不以其中的 NaN/Inf 判定失败；任何有效事件的 payload 仍必须完整通过数值检查。
