# BSDF 透射 `/η²` 缺失调查报告

> 历史说明：本文记录旧版折射型生产材质及固定 RoboCute 参考闭包的调查。当前 Prime
> 生产透明材质不再折射：只有相机首透明面的反射支使用参考 Fresnel/GGX，直透支及后续
> 透明面方向不变。玻璃改为逐面滤色且不进入介质栈，水只保留按路径距离计算的体积吸收。
> 受保护参考闭包及其逐 bit/属性测试仍保留，因此下述参考问题、版本锁定和证据链仍有历史
> 与回归价值，但不应再解释为当前玻璃传输语义。

- 日期：2026-07-25
- 状态：已修复并验收；上游 `/η²` 修复已更新版本锁定，Prime 适配层已补齐出射界面的有符号方向/相对 IOR 契约，GPU 回归与游戏内目测均通过
- 影响范围：非薄壁介质的镜面与粗糙微表面透射
- 不影响范围：薄壁/叶片透射、反射事件、BSDF PDF、透射方向、体积栈和透明重投影的相对 IOR

## 结论

Prime 原先固定使用的 RoboCute 版本在辐亮度传输（Radiance transport）下存在一处内部契约不一致：

- 粗糙介质透射的评估函数 `eval(wi, wo)` 包含 `1 / etaPath²`。
- 同一事件的采样函数 `sample(wi, random)` 返回响应时遗漏该因子。
- 两条路径计算的方向和 PDF 一致，因此积分器使用 `response / pdf` 时会产生系统性、有方向的能量偏差。
- 同一采样器的狄拉克（delta）透射分支也遗漏该辐亮度因子；虽然 delta 事件没有普通有限立体角的 `eval()` 对照，PBRT-v4 的独立实现和辐亮度传输约定共同确认它需要相同修正。

RoboCute 已在 `0d982c77b3fd26c2c5a3c0852be3bd05e5860bd8` 中修复 `/η²` 问题：采样器和评估器现在都按显式 transport mode 应用相同的 eta scale，采样结果同时返回实际使用的相对 IOR。Prime 已锁定该版本并移除原有的重复补偿适配。

该 commit 同时引入厚介质出射面的材质坐标变换，但没有同步闭包核心赖以选择相对 IOR 的半球语义。参考折射核心原本用 `wi.z < 0` 表示离开介质，并在内部选择 `etaPath = n_outside / n_material`；新包装层把出射方向旋转到 `wi.z > 0`，状态却仍保存 `n_material / n_outside`。这会让平行玻璃板的两个界面都错误使用 `eta = 1.5`，辐亮度缩放累乘为 `1 / 1.5⁴ ≈ 0.19753`，并同时破坏出射 Snell 方向和全反射判断，是本次“玻璃变黑”的直接原因。

直接把状态 IOR 改成 `1 / 1.5` 只能修正 delta 折射方向和 eta scale，仍不完整：参考 Fresnel RT/PDF 对 `ior < 1 且 wi.z > 0` 的粗糙 TIR 分支与 sampler 的显式折射有效性判断不一致，GPU 闭包测试实际捕获到 sampler 与 evaluator 的 PDF/响应相差约 `1.83×`。因此 Prime 采用更小且一致的适配：保留参考折射核心已经支持的 `wi.z < 0、ior > 1` 出射表示，只绕过新增的包装层旋转，并用原始有符号方向重新计算 transmission-GGX 多次散射状态。

## 传输约定

Prime 的 RoboCute 适配使用以下响应约定：

```text
value = f(wi, wo) * abs(wo.z)
路径乘数 = value / pdf
```

折射会改变方向所对应的立体角测度。辐亮度传输必须按折射路径的相对 IOR 处理这一非对称性。在当前方向约定下：

```text
etaPath = wi.z > 0 ? ior : 1 / ior
辐亮度透射响应 *= 1 / etaPath²
```

这个因子属于 BSDF 响应，不属于 PDF，也不是 Fresnel 透射率的一部分。路径携带的 `relativeEta` 是独立元数据；Prime 用它重建透明 delta 光路，PBRT-v4 用类似的 `etaScale` 调整俄罗斯轮盘终止。二者都不会自动补偿响应中缺失的 `/η²`。

## 证据链

### 1. RoboCute 历史参考版本

发现问题时 Prime 的固定参考：

- 仓库：<https://github.com/RoboCute/RoboCute>
- commit：`5985e989254b4685e3885d876b33f4874d233dcd`
- 本地副本：`C:\WorkSpace\_ref\RoboCute`

文件：

```text
rbc/shader/include/bsdfs/base/refractive_fresnel_microfacet.hpp
```

粗糙透射评估器在构造响应后明确执行：

```cpp
result.val = F.second * D * microfacet.G2t(wi, wo)
        * abs(moi * moo / denom);
// only available in radiance transport mode
result.val /= sqr(etap);
```

同文件的采样器使用相同的微表面项和透射雅可比，但没有最后的除法：

```cpp
T *= D * microfacet.G2t(wi, wo)
        * abs(moi * moo / (wi.z * denom));
result.throughput.val = T;
```

delta 透射也直接返回菲涅耳透射量 `T`，没有辐亮度模式所需的 `/etap²`。

### 2. Prime 历史参考移植逐式复现

发现问题时，受保护文件 `shaders/robocute_bsdf_microfacet.glsl` 与旧固定参考一致：

- `primeRcRefractiveEval()` 的粗糙透射响应包含
  `/ primeRcSquare(etaPath)`。
- `primeRcRefractiveSample()` 的粗糙和 delta 透射响应都不包含该因子。

当时的版本锁定与 GPU 见证证明 Prime 没有在移植时遗漏该因子。当前受保护文件已经随新固定参考更新，采样和评估均包含上游正式修复。

### 3. 固定数值见证

调查阶段对同一个粗糙透射样本重新调用评估器，`ior = 1.5` 时观察到：

```text
采样响应 = 30.360151
评估响应 = 13.493405
采样 PDF ≈ 71.7597
评估 PDF ≈ 71.7597
响应比值 = 2.25 = 1.5²
```

PDF 相等而响应恰好相差 `etaPath²`，把问题定位到采样器的辐亮度响应，而不是微表面采样分布、雅可比或评估器 PDF。

反向离开介质时 `etaPath = 1 / 1.5`。未修复采样响应与正确响应的比例变为 `1 / 2.25`，因此该问题不只是统一的曝光偏移，还会破坏进入/离开介质两侧的能量关系。

### 4. 独立统计积分

修复前的固定 GPU 统计用例得到：

```text
采样器返回响应的蒙特卡洛能量 ≈ 0.907836
独立等立体角评估器积分       ≈ 0.442494
对已采样方向重新评估         ≈ 0.442549
```

独立积分和重新评估一致，只有采样器自带响应偏离。这排除了积分网格、LUT 和随机方向分布作为主要成因。

### 5. PBRT-v4 交叉验证

本地 PBRT-v4：

- 路径：`C:\WorkSpace\_ref\pbrt-v4`
- commit：`5f7a606806a4ac7b939131ded9d7a30ebd02416e`

CPU `PathIntegrator` 的 BSDF 采样和直接光照评估都没有显式传入模式：

```cpp
bsdf.Sample_f(wo, u, sampler.Get2D());
bsdf->f(wo, wi);
```

`BSDF::Sample_f()`、`BSDF::f()` 和 `BSDF::PDF()` 的默认模式均为
`TransportMode::Radiance`。PBRT-v4 的 `DielectricBxDF` 在以下位置都执行
`ft /= Sqr(etap)`：

- delta 透射采样
- 粗糙透射采样
- 粗糙透射评估

PBRT-v4 还把真实路径通量和 RR 补偿明确分开：

```cpp
beta *= bs->f * AbsDot(bs->wi, n) / bs->pdf;
if (bs->IsTransmission())
    etaScale *= Sqr(bs->eta);
rrBeta = beta * etaScale;
```

`etaScale` 只用于俄罗斯轮盘终止，不写回真实 `beta`。这证明相对 IOR 路径状态不能代替辐亮度 BSDF 响应中的 `/η²`。

光源路径则对采样和评估都显式使用 `TransportMode::Importance`。因此模式由路径传输方向决定，不是采样和评估各用一种模式。

## 根因与责任划分

### 根因来源

算法不一致已存在于固定 RoboCute commit 的原始着色器中。评估器明确实现辐亮度 `/η²`，配套采样器没有实现。因此源码缺陷来源属于该固定上游版本。

### Prime 移植责任

受保护的 `robocute_bsdf_*.glsl` 逐式复现参考实现，没有引入该缺失。按照 Prime 的参考一致性规则，不应直接修改这些文件，也不应以格式化、数值保护或重构的名义改变它们。

### Prime 集成责任

Prime 选择并运行该参考实现，因此仍负责保证最终积分器契约正确。上游修复前，Prime 曾在独立适配层补偿缺失因子；锁定新版本后该补偿已删除，避免对采样响应重复应用 `/η²`。

### 非责任方

- GGX transmission energy LUT 负责方向能量/多次散射补偿，不能表达逐事件、分介质方向的 Radiance 测度变换。
- NRD 和 DLSS RR 处理已经生成的帧数据，不能恢复路径积分时遗漏的 BSDF 能量因子。
- PDF 与采样方向在固定见证中相互一致，不是本问题的来源。

## 上游修复与当前同步方式

当前固定参考：

```text
仓库：https://github.com/RoboCute/RoboCute
commit：0d982c77b3fd26c2c5a3c0852be3bd05e5860bd8
文件：rbc/shader/include/bsdfs/base/refractive_fresnel_microfacet.hpp
```

上游引入 `TransportMode`，Radiance 模式下的评估与采样统一执行：

```cpp
T *= transport_eta_scale(etap, data);
```

并在采样记录中保存：

```cpp
result.eta = etap;
```

Prime 的受保护 GLSL 移植同步了相同的表达式和求值顺序。`primeRcPrimeTransmissionSample()` 现在只负责 Prime 的有界介质栈生命周期，不再修正 BSDF 能量；透明重投影的 `relativeEta` 直接采用参考采样结果。同步还包含厚玻璃出射面的材质坐标变换和色散 IOR 组合顺序。

由于固定上游只变换了出射方向而没有同步折射核心的半球语义，Prime 在适配层恢复以下事件契约：

```text
进入：eta = n_material / n_outside
离开：eta = n_outside / n_material
```

`primeRcPrimeTransmissionInterfaceState()` 保留材质/外部介质的前向 IOR，并以原始负半球出射方向重新查询 transmission-GGX 多次散射补偿。`primeRcPrimeTransmissionClosureState()` 只在调用受保护 sample/eval/pdf 的临时状态上禁用新增坐标旋转；真实 `state.entering` 保留给 Prime 的体积栈 push/pop。折射核心由 `wi.z` 统一决定实际事件的 `etaPath`，因此 Snell 方向、TIR、Fresnel、PDF、`/η²` 和返回的 `relativeEta` 不再分裂。

上游本次没有合入 2026-07-24 作者提供的 44×32×159 HALF4 transmission-GGX 能量表及分支化多次散射补偿，因此该权威 overlay 继续保留并独立校验。

上游的新嵌套介质优先级积分器没有进入 Prime。Prime 按既有决策保留两层有界介质栈；这是已知性能权衡，不改变本报告所述接口采样、eta scale 和出射坐标修复。

其中 `/η²` 修复本身不会改变：

- `wo` 和折射定律
- 采样/评估 PDF
- Fresnel 反射与透射概率
- event flags
- 薄壁/叶片透射
- 2026-07-24 作者 LUT overlay

## 已有自动回归

- 公共函数、菲涅耳和微表面 GPU 属性测试检查粗糙透射采样/评估的响应和 PDF 一致性。
- delta 透射属性按辐亮度菲涅耳响应 `/etaPath²` 检查采样器返回值。
- 闭包测试通过 Prime 生产适配入口覆盖非薄壁和薄壁材质、方向两侧、IOR/粗糙度边界、事件与体积栈状态。
- 平行介质板 GPU 属性测试覆盖 `ior = 1.01..2.5` 与正视到掠射入射，检查进入/离开相对 IOR 乘积为 1、两次辐亮度 eta scale 互相抵消、Snell 方向往返、临界角以上 TIR，以及体积栈 push/pop。
- 统计测试分别比较采样器蒙特卡洛能量、已采样方向的重新评估能量和独立等立体角积分。
- 强制 Vulkan `shaderTest` 当前四套测试均通过，且没有 skipped。
- 完整 `test`、`build`、`verifyDistributionJar` 和 `verifyRoboCutePort` 当前通过。

这些测试证明数学契约和当前 GPU 执行结果一致，但不代替不同场景下的成像 A/B 与游戏内主观检查。

## 当前验收

- GPU 属性测试覆盖非薄壁粗糙与 delta 透射、进入/离开界面、相对 IOR、Snell 往返、
  TIR、PDF、响应和 `/η²` 互消关系。
- 薄壁路径由独立属性保持不变。
- 游戏内目测确认原有高光能量异常已经消失，玻璃亮度恢复，未观察到新的系统性能量跳变。
- 若未来更换 RoboCute 版本、传输模式或介质坐标约定，必须重新执行上述自动回归并重新目测；
  本报告的证据链继续作为定位基线。
