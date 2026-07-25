# Wavefront 双分支主积分器实验

## 目标

本实验从实时 raygen 中移除 megakernel 主积分路径，让固定轮数的 wavefront 阶段承担完整主积分。截图/参考累积继续使用独立参考积分器，不参与实时队列。

透明主表面仍同时生成物理反射与透射两条路径，不采用随机单分支。两条路径是同一种队列记录中的独立工作项，共享 step、transition 和 tail 调度；分支类型只作为路径状态，不产生两套积分管线。每轮 trace 的 SER 重排因此可以同时接收两类路径，并按实际命中着色器重组执行。

## 当前数据流

1. `head` 追踪首个表面。普通表面生成一个活动路径；透明表面固定生成透射与反射两个活动路径。
2. 路径状态仍拥有稳定 ID：槽 0 保存普通/透射路径，槽 1 保存反射路径。`head` 只把真正需要继续的 ID append 到活动队列。
3. 前 11 轮 `step` 执行完整 NEE，`transition` 切换到仅太阳 NEE，`tail` 在局部循环中结束残余路径。
4. 每轮从一个紧凑索引队列读取，只把仍活动的路径 append 到另一个队列；两个队列 ping-pong，并直接把活动计数作为下一次 `vkCmdTraceRaysIndirectKHR` 的宽度。
5. 每条透明路径独立保存辐射、介质、降噪引导与 PSR 状态；`resolve` 最后按像素合并反射、透射和首表面引导，再写入现有 NRD、DLSS RR 或无后处理输出。

透明与不透明路径共用活动队列和 SER 重排点。当前压缩的是活动 ID，未执行显式全局 radix sort；稳定路径状态仍按最坏情况保留两个槽，因此压缩直接减少空 invocation 和队列流量，但不按实际透明像素数动态缩小路径状态池。

## 队列 ABI 与显存

每个路径槽的 `PrimeWavefrontPathRecord` 为 144 字节，每像素固定两个状态槽。原来的 96 字节共享首表面记录已经删除：可见法线复用最终 normal/roughness 图像，可见位置在 NRD 模式复用 display-position 图像，RR/无后处理模式从相机射线和保存的距离重建；透明主表面不用的稳定/SIGMA 通道在 `resolve` 前暂存必要的反射信号和材质。

两个 ping-pong 活动队列各按最坏情况保留 `2N` 个 32-bit 路径 ID，另有两个 16 字节间接命令：

`144 × 2 + 4 × 2 × 2 = 304 字节/内部渲染像素，另加 32 字节`

| 内部渲染分辨率 | 队列显存 |
| --- | ---: |
| 1920×1080 | 约 601 MiB |
| 2560×1440 | 约 1.04 GiB |
| 3840×2160 | 约 2.35 GiB |

相对上一版 384 字节布局，稳态队列显存下降约 20.8%。实际 DLSS 内部渲染分辨率通常低于显示分辨率，但原生高分辨率模式仍应继续评估分离冷热状态、按阶段缩减记录、分块路径池和可控容量上限。

创建队列前会分别校验路径区、索引队列区、`maxStorageBufferRange` 和 `maxRayDispatchInvocationCount`。活动队列需要 `rayTracingPipelineTraceRaysIndirect`；设备协商阶段会明确验证并启用该特性。

## 已验证契约

- ABI 固定为 12 轮、144 字节路径记录、每像素两个路径槽、两个紧凑索引队列、16 字节间接命令和 32-bit 路径 ID。
- CPU 只创建两个 raygen shader module：参考积分器和统一 wavefront；head、两种 queue 方向的 step/transition/tail、resolve 通过 SBT record 选择入口。
- 实时 wavefront SPIR-V 中已无 `primeIntegrateWithVolume` 或 `primeIntegrate` 调用，megakernel 主积分路径可被死代码消除。
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
