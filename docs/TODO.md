# 后续工作

本页只记录非缺陷型增强；可复现的当前问题见 [FIXME](FIXME.md)。

## 生命周期与地形性能

- 按 Vulkan timeline 完成点循环复用 descriptor set/pool，保持在途 command buffer 的资源
  所有权不变，减少 TLAS 替换时的驱动分配；
- 评估把 Section geometry 和 light record 直接写入可增长 staging/native 存储，减少
  Section→cluster 所有权转移期间的 Java 数组驻留，同时保持单 cluster 原子替换和单 base
  BLAS/TLAS instance；
- 为纹理体素表面设计跨 cluster GPU mesh 池，复用相同纹理、UV 和朝向生成的
  BLAS、primitive 与 OMM，同时明确 resource epoch、引用所有权、compaction 注册和退休顺序；
- 评估地形静态更新更有效的批处理；动态捕获固定逐帧重建 BLAS/TLAS，不做 dirty check
  或 refit。新 geometry 不得在可见性结构外提前发布。
- 评估让同一 motion/lifetime domain 内的动态表面复用静态 `SurfaceDefinition` resolver；必须
  保持逐帧 resident 所有权、previous-position 对应和无法证明关系时的原几何回退。

## 渲染能力

- 重新设计 Reinhard-Gamut 的 HDR shoulder。当前 `+6.5 EV` reach 随显示峰值 headroom
  整体放宽中灰以上曲线，数学上能在有限输入命中峰值，但会过度抬升太阳等中高亮，达不到
  艺术需求；后续应明确 SDR 参考白以下的外观保持边界，并只用额外 headroom 展开 HDR 高光；
- 场景几何 LOD；
- 云渲染（细节待定）；
- 将当前已读取但尚未参与着色的切线空间法线接入闭包，并为 normal-map handedness、
  动画、relief 共存和重建 guide 建立一致契约；
- 评估 LabPBR AO/porosity 的物理用途；不能把源格式字节直接泄漏到积分器或用环境遮蔽重复
  压暗已经由路径追踪求出的间接光；
- 月亮（月相）：作为夜晚的主光源；月相跟随 Minecraft 原版状态，轨迹取太阳轨迹的
  相对方向，不采用真实月球轨迹；
- 雾和局部体积光的通用体积渲染；
- 将实体及动态发光几何纳入灯光采样，并定义 emitter 捕获、增量 light tree、生命周期和
  前后向 PDF；
- 评估 NVIDIA Streamline 的能力探测、资源标记和生命周期，保留不支持时的明确回退。

## 兼容性与产品体验

- 重新实现小地图；Prime 的自定义场景渲染破坏了现有小地图 mod 所依赖的原版渲染兼容性，
  需要提供不依赖原版世界渲染管线的地图数据与绘制路径；
- 检测玩家手持的发光物品，并将其作为动态光源纳入场景光照；
- 检测会绕过或重复动态实体提交、替换世界渲染或破坏后处理边界的已知不兼容 Mod，并在启用
  Prime 前给出具体 Mod ID、受影响能力和禁用建议；检测必须基于可证明的加载或注入契约，
  不得按模组类别猜测，也不得用不可靠兼容层掩盖错误渲染；
- 为着色器与管线编译提供可见进度条；
- 调查机械动力飞跃版等 mod 的实体捕获、render type、动态 geometry 和 TLAS 路径；
- 改进未按 `texture.properties` 标准声明的 LabPBR 资源包检测，同时避免误判普通纹理；
- 为高分辨率纹理体素表面测量 CPU 建网、BLAS、staging/显存和 instance 增长，随后定义可诊断
  的分辨率上限、保形降采样或标准 quad 回退。降级不得改变 alpha、UV 或位移上限。
