# BSDF 透射 `/η²` 缺失调查报告

- 日期：2026-07-25
- 状态：根因已确认，Prime 适配层修复与 GPU 回归已完成；A/B 对照实验和游戏内目测待办
- 影响范围：非薄壁介质的镜面与粗糙微表面透射
- 不影响范围：薄壁/叶片透射、反射事件、BSDF PDF、透射方向、体积栈和透明重投影的相对 IOR

## 结论

Prime 固定使用的 RoboCute 版本在辐亮度传输（Radiance transport）下存在一处内部契约不一致：

- 粗糙介质透射的评估函数 `eval(wi, wo)` 包含 `1 / etaPath²`。
- 同一事件的采样函数 `sample(wi, random)` 返回响应时遗漏该因子。
- 两条路径计算的方向和 PDF 一致，因此积分器使用 `response / pdf` 时会产生系统性、有方向的能量偏差。
- 同一采样器的狄拉克（delta）透射分支也遗漏该辐亮度因子；虽然 delta 事件没有普通有限立体角的 `eval()` 对照，PBRT-v4 的独立实现和辐亮度传输约定共同确认它需要相同修正。

RoboCute 的受保护 GLSL 移植忠实保留了固定参考版本的表达式和求值顺序。缺失不是 Prime 移植时引入的，也不能由 GGX energy LUT、NRD 或 DLSS RR 修复。

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

### 1. RoboCute 固定参考版本

Prime 的固定参考：

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

### 2. Prime 参考移植逐式复现

受保护文件 `shaders/robocute_bsdf_microfacet.glsl` 与固定参考一致：

- `primeRcRefractiveEval()` 的粗糙透射响应包含
  `/ primeRcSquare(etaPath)`。
- `primeRcRefractiveSample()` 的粗糙和 delta 透射响应都不包含该因子。

`verifyRoboCutePort` 当前通过，且 `shaders/robocute_bsdf_*.glsl` 没有因本问题被修改。因此可以排除 Prime 端漏译、重排表达式或改变参考算法的可能。

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

Prime 选择并运行该参考实现，因此仍负责保证最终积分器契约正确。当前采用独立适配层修复，使参考副本保持可验证，同时消除实际渲染偏差。

### 非责任方

- GGX transmission energy LUT 负责方向能量/多次散射补偿，不能表达逐事件、分介质方向的 Radiance 测度变换。
- NRD 和 DLSS RR 处理已经生成的帧数据，不能恢复路径积分时遗漏的 BSDF 能量因子。
- PDF 与采样方向在固定见证中相互一致，不是本问题的来源。

## 当前修复

修复位于：

```text
shaders/prime_bsdf_specializations.glsl
```

`primeRcPrimeCorrectTransmissionSample()` 只在以下条件成立时修改响应：

```text
geometryThinWalled == false
event == SPECULAR_TRANSMISSION 或 DELTA_TRANSMISSION
```

修正内容：

```glsl
float etaPath = wi.z > 0.0
        ? state.specularFresnel.ior
        : 1.0 / state.specularFresnel.ior;
sampleValue.throughput.value /= primeRcSquare(etaPath);
```

生产调用通过 `primeRcPrimeTransmissionSample()` 进入该适配层。修复不会改变：

- `wo` 和折射定律
- 采样/评估 PDF
- Fresnel 反射与透射概率
- event flags
- volume stack 和 `rayT`
- Prime `relativeEta`
- 薄壁/叶片透射
- 受保护 RoboCute 参考文件

## 已有自动回归

- 公共函数、菲涅耳和微表面 GPU 属性测试检查粗糙透射采样/评估的响应和 PDF 一致性。
- delta 透射属性按辐亮度菲涅耳响应 `/etaPath²` 检查采样器返回值。
- 闭包测试通过 Prime 生产适配入口覆盖非薄壁和薄壁材质、方向两侧、IOR/粗糙度边界、事件与体积栈状态。
- 统计测试分别比较采样器蒙特卡洛能量、已采样方向的重新评估能量和独立等立体角积分。
- 强制 Vulkan `shaderTest` 当前四套测试均通过，且没有 skipped。
- 完整 `test`、`build`、`verifyDistributionJar` 和 `verifyRoboCutePort` 当前通过。

这些测试证明数学契约和当前 GPU 执行结果一致，但不代替不同场景下的成像 A/B 与游戏内主观检查。

## 待办：受控 A/B 对照实验

- [ ] 固定同一 commit、GPU、驱动、着色器变体、随机种子、采样数、分辨率和曝光，构建：
  - A：禁用 Prime `/η²` 适配的基线。
  - B：启用当前粗糙透射和 delta 透射修复。
- [ ] 使用单界面测试隔离进入和离开介质，至少覆盖 `ior = 1.333 / 1.5 / 2.4`。
- [ ] 分别覆盖 delta、接近光滑判定阈值、`roughness = 0.05 / 0.25 / 0.5`。
- [ ] 禁用自动曝光，输出线性 HDR/EXR；同时保存响应、PDF、`response/pdf`、事件、`relativeEta` 和路径方向。
- [ ] 验证非薄壁透射的理论比值：
  - 进入 `ior = 1.5` 时，未修复 A 相对 B 应高约 `2.25×`。
  - 离开同一介质时，未修复 A 相对 B 应低约 `1 / 2.25×`。
- [ ] 对每个用例计算平均值、方差、置信区间和 A−B 差异热图，不以单个高方差像素下结论。
- [ ] 增加双界面平板和封闭水体，确认成对界面、吸收、体积栈与俄罗斯轮盘终止没有产生新的系统偏差。
- [ ] 单独确认薄壁材质 A/B 数值逐 bit 不变。
- [ ] 保存实验配置、原始输出和汇总表，并把可复现命令补充到本报告。

## 待办：游戏内目测

- [ ] 固定时间、天气、相机、曝光、分辨率、渲染比例和材质包，分别截取 A/B。
- [ ] 检查空气看入水体、从水下看空气，以及玻璃内外两侧的亮度关系。
- [ ] 检查默认玻璃、染色玻璃、连续玻璃层、水面加玻璃等多层透明组合。
- [ ] 检查正视角、掠射角和全反射临界角附近；临界角的有限精度噪声与本能量缺失分开记录。
- [ ] 分别观察 delta 和粗糙透射，确认没有新的过亮、过暗、色偏、亮点噪声（firefly）或能量跳变。
- [ ] 在去噪关闭时检查原始收敛与噪声分布，再开启 NRD/DLSS RR 检查历史稳定性；不得用去噪结果掩盖 BSDF 偏差。
- [ ] 静止足够长时间并录制相机运动，检查水下太阳、多层透明和透明掠射角的拖影/闪烁是否变化。
- [ ] 确认薄壁叶片、草和默认薄玻璃的现有外观没有受到非薄壁修正影响。
- [ ] 记录最终结论、截图路径、运行配置和已知剩余偏差。

## 验收标准

只有同时满足以下条件，才能把视觉验证标记为完成：

- 数值 A/B 符合 `1 / etaPath²` 的预期方向和比例，且采样/评估/PDF 契约保持一致。
- 非薄壁粗糙与 delta 透射均通过进入/离开介质测试。
- 薄壁路径保持不变。
- 固定曝光的游戏内 A/B 没有发现新的能量跳变、色偏或稳定性退化。
- 原始路径追踪结果和去噪结果分别检查并留存证据。
