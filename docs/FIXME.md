# FIXME

## 性能：地形流送期间的 Java 堆分配风暴

- 状态：主要分配源已修复，仍需实机场景验证与 TLAS 更新策略研究
- 类型：CPU 地形构建存储与 TLAS 全量更新成本

### 现象

在较小 Java 堆的发行实例中，进入高视距世界并持续加载 Section 时，帧率会缓慢下降，最终因频繁 GC 接近卡死。手动提高 JVM 的最大堆可以显著延后并缓解问题；开发运行通常拥有更大的默认堆，因此不容易立即复现。

构建 JAR 中相关 Java class 与 SPIR-V 已和开发运行产物逐项核对，内容相同。这不是发行构建选择了不同代码或 shader permutation 导致的固定性能差异。

### 已确认原因

- Section 以小批次上传时，TLAS 实例表与 GPU TLAS 仍会针对全部驻留 virtual cluster 更新。
- CPU mesh 在 Section 输出合并成 4×4×4 virtual cluster 时使用有界 segment，避免创建一个与整个 cluster 等大的 Java 数组；原子上传仍需要与完整 generation 对应的 staging 和 GPU 分配。
- 大堆主要通过降低 GC 频率掩盖分配速率，不能解决算法复杂度、内存带宽或最坏情况下的停顿。

诊断中曾观察到约一百万个已分配的灯光树 `Bounds`、数万节点和约 4.6 万个单次 TLAS Section 实例。该统计包含尚未回收的不可达对象，说明的是分配风暴，不应直接解读为等量的永久泄漏。

当前实现已落地以下整改：

- 两级灯光树的 leaf、node、emitter 与 SAH 工作区均改为紧凑 primitive 数组；SAH 使用单次前缀/后缀聚合，fallback sort 不分配对象。
- 世界灯光树由渲染线程单独拥有稳定叶槽；普通替换、卸载与原点移动执行 O(n) refit，仅在容量、利用率或 SAH 成本明确退化时完整重建。
- mesh 三段合并只分配一次最终数组；GPU 上传后驻留对象只保留世界树所需的灯光摘要。
- 跨世界 worker 数量不会重置，worker/completed/ready 三阶段共享一个任务上限。一个 logical cluster 始终只生成一个 BLAS/TLAS instance，但 CPU 输入可拥有任意数量 segment；128K 三角形/约 16 MiB 只是 segment 工作集目标，不是内容拒绝上限。实际在途内存由内容规模和资源可用量决定，资源耗尽会明确失败而不会丢弃几何。

这些改动消除了已确认的无界存活堆与主要小对象风暴，但 TLAS 全量实例写入/GPU build 和 Section→cluster 的有界双份数组仍存在。

### 临时规避

在启动器中手动提高 Minecraft 的 JVM 最大堆（`-Xmx`），并根据机器物理内存保留足够的系统余量。该做法仅作为开发期临时方案，不应成为 Prime 的最终运行要求。

不要通过热路径调用 `System.gc()`、放宽无界队列或永久保留更大的中间缓存来规避问题。

### 重写要求

- 评估 TLAS `ALLOW_UPDATE`/refit 或低延迟批处理；不得让新 geometry 在可见性结构之外形成错误画面。
- 评估将 CPU segment 直接流式写入可复用 staging 区域，同时保持一个 cluster 只有一个 BLAS 和一次原子 resident 替换；不得以增加 TLAS instance 换取较低上传峰值。
- 在常见的小堆发行配置、高视距跑图和复杂发光地形下验证稳定分配速率、GC 停顿、队列上限及长期帧时间。
