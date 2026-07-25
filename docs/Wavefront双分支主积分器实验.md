# Wavefront 双分支主积分器实验

## 目标

固定轮数的 wavefront 阶段现在承担实时与截图/参考累积的完整主积分，旧 megakernel raygen 已删除。两种模式只在 `resolve` 的输出策略上不同：实时写 NRD、DLSS RR 或无后处理输入，截图应用 aerial perspective 后写 RGBA32F 运行均值。

透明主表面仍同时生成物理反射与透射两条路径，不采用随机单分支。两条路径是同一种队列记录中的独立工作项，共享 step、transition 和 tail 调度；分支类型只作为路径状态，不产生两套积分管线。每轮 trace 的 SER 重排因此可以同时接收两类路径，并按实际命中着色器重组执行。

## 当前数据流

1. `head` 追踪首个表面。普通表面生成一个活动路径；透明表面固定生成透射与反射两个活动路径。
2. 路径状态仍拥有稳定 ID：槽 0 保存普通/透射路径，槽 1 保存反射路径。`head` 只把真正需要继续的 ID append 到活动队列。
3. 前 11 轮 `step` 执行完整 NEE，`transition` 切换到仅太阳 NEE，`tail` 在局部循环中结束残余路径。
4. 每轮从一个紧凑索引队列读取，只把仍活动的路径 append 到另一个队列；两个队列 ping-pong，并直接把活动计数作为下一次 `vkCmdTraceRaysIndirectKHR` 的宽度。
5. 每条透明路径独立保存辐射、介质、降噪引导与 PSR 状态；`resolve` 最后按像素合并反射、透射和首表面引导，再选择实时重建输入或截图运行均值输出。
6. 总反弹硬上限为 128。俄罗斯轮盘赌仍从第 1 次续接后开始，所以上限只截断极端幸存尾部。

透明与不透明路径共用活动队列和 SER 重排点。当前压缩的是活动 ID，未执行显式全局 radix sort；稳定路径状态仍按最坏情况保留两个槽，因此压缩直接减少空 invocation 和队列流量，但不按实际透明像素数动态缩小路径状态池。

## 队列 ABI 与显存

每个路径槽的 `PrimeWavefrontPathRecord` 为 144 字节，每像素固定两个状态槽。原来的 96 字节共享首表面记录已经删除：可见法线复用最终 normal/roughness 图像，可见位置在 NRD 模式复用 display-position 图像，RR/无后处理模式从相机射线和保存的距离重建；透明主表面不用的稳定/SIGMA 通道在 `resolve` 前暂存必要的反射信号和材质。

144 字节是九条连续 16 字节 lane，不包含布局空洞。活动轮次只逐成员读取和写回前六条 lane：64 字节 f32 传输核心加 32 字节双层介质栈。16 字节首表面 area-light moment 在 `head` 后只读，32 字节 PSR 只在启用透明引导且尚未找到 guide 时访问；找到 guide 后不再写回。固定槽的稳态分配尚未缩小，但普通路径每轮的记录流量已从 144 字节读加 144 字节写降到 96 字节读加 96 字节写。

普通活动路径只往返 diffuse/specular 两组热信号；首表面材质、法线、位置、太阳项和方向不再在每个 bounce 重写。尚未结束 delta guide 链时额外读取并按需更新 specular albedo。透明路径同样只持续更新分支辐射和 hit metadata，完整虚拟表面 guide 仅在首次建立时写入。

两个 ping-pong 活动队列各按最坏情况保留 `2N` 个 32-bit 路径 ID，另有两个 16 字节间接命令：

`144 × 2 + 4 × 2 × 2 = 304 字节/内部渲染像素，另加 32 字节`

| 内部渲染分辨率 | 队列显存 |
| --- | ---: |
| 1920×1080 | 约 601 MiB |
| 2560×1440 | 约 1.04 GiB |
| 3840×2160 | 约 2.35 GiB |

相对上一版 384 字节布局，稳态队列显存下降约 20.8%。实际 DLSS 内部渲染分辨率通常低于显示分辨率，但原生高分辨率模式仍应继续评估分离冷热状态、按阶段缩减记录、分块路径池和可控容量上限。

创建队列前会分别校验路径区、索引队列区、`maxStorageBufferRange` 和 `maxRayDispatchInvocationCount`。活动队列需要 `rayTracingPipelineTraceRaysIndirect`；设备协商阶段会明确验证并启用该特性。

截图会话必须在原生分辨率保存同一套 wavefront 暂存状态，不能借用可能较低分辨率的实时 NRD/RR 输入。`ScreenshotRenderResources` 独占原生分辨率的 `BasicWavefrontSignals`、稳定辐射 scratch、RGBA32F 运行均值和显示输出，退出后整体延迟释放；实时与截图只通过 `WavefrontSignals` 契约复用布局语义，不共享图像所有权。截图 scratch 只声明 storage usage，实时稳态显存不受此项影响。

## 已验证契约

- ABI 固定为 12 轮、144 字节路径记录、每像素两个路径槽、两个紧凑索引队列、16 字节间接命令和 32-bit 路径 ID。
- CPU 只创建一条完整 wavefront RT pipeline。发布构建预先冻结 specialization constant，
  将同一 GLSL 源裁成 head、step/transition、tail 和 resolve 四个 raygen stage 模块；
  queue 方向、transition 与截图输出标记仍由 SBT record 提供。主 step 因此不继承
  head/tail/resolve 的寄存器上限，也不要求驱动在冷启动时重复裁剪整个统一模块。
- 支持 RayGen subgroup ballot 的 SER 设备使用 subgroup 聚合 append：一个 subgroup 只执行一次全局计数器分配，并连续写入所有存活路径。无此能力的设备继续使用逐路径原子 fallback。
- SER coherence hint 使用六位 section 局部性和两位路径类别，区分普通、透明反射和透明透射。
- 主追踪 payload 从 80 字节降到 64 字节；命中位置由 raygen 根据 `origin + direction × t` 重建，完整 `SurfaceInteraction` 的公开 ABI 不变。
- 实时和截图共用同一组 15 次 dispatch 与一套队列状态；截图不再创建或编译独立 raygen。
- 光追 descriptor 不再包含已经无人读写的最终显示输出；显示图像变化不会使主积分 descriptor 缓存失效。
- 新增的 queued PSR 属性测试用显式 delta 链对照四元数压缩表达，覆盖 1–8 个 delta 事件及其全部随机反射组合。
- Java 单元测试、完整 GPU shader 属性测试、glslang 编译、SPIR-V 优化与验证均通过。

## 目测与性能验收

- [ ] 普通不透明场景与主线画质一致。
- [ ] 透明反射和透射同时存在，不出现随机单分支伪影。
- [ ] NRD、DLSS RR、无后处理三种模式的透明引导均稳定。
- [ ] 透明多层、掠射角、太阳高光和夜间星空反射不新增拖影或闪烁。
- [ ] 对比主线记录驱动首次编译时间、实时帧时、寄存器数、occupancy、显存峰值和稳态显存。
- [ ] 用 Nsight 确认间接宽度随 bounce 单调收缩，并测量全局 append counter 的竞争成本。
- [ ] 根据显存目标评估分块路径状态池或冷状态拆分。
- [ ] 测试截图运行均值连续累积，且切换 NRD、DLSS RR 与内部缩放比例后仍保持原生分辨率。
