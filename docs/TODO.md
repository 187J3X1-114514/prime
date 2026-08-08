# 后续工作

本页只记录非缺陷型增强；可复现的当前问题见 [FIXME](FIXME.md)。

## 生命周期与地形性能

- 按 Vulkan timeline 完成点循环复用 descriptor set/pool，保持在途 command buffer 的资源
  所有权不变，减少 TLAS 替换时的驱动分配；
- 评估把 Section geometry 和 light record 直接写入可增长 staging/native 存储，减少
  Section→cluster 所有权转移期间的 Java 数组驻留，同时保持单 cluster 原子替换和单 base
  BLAS/TLAS instance；
- 评估 TLAS refit 或更有效的批处理；新 geometry 不得在可见性结构外提前发布。

## 渲染能力

- 云、雾、水和局部体积光的通用体积渲染；
- 将实体及动态发光几何纳入灯光采样，并定义 emitter 捕获、增量 light tree、生命周期和
  前后向 PDF；
- 评估 NVIDIA Streamline 的能力探测、资源标记和生命周期，保留不支持时的明确回退。

## 兼容性与产品体验

- 调查机械动力飞跃版等 mod 的实体捕获、render type、动态 geometry 和 TLAS 路径；
- 改进未按 `texture.properties` 标准声明的 LabPBR 资源包检测，同时避免误判普通纹理；
- 为高分辨率纹理体素表面测量 CPU 建网、BLAS、staging/显存和 instance 增长，随后定义可诊断
  的分辨率上限、保形降采样或标准 quad 回退。降级不得改变 alpha、UV 或位移上限。
