# FX11 CarPlay 空调总开关：首期协议移植

> 本文记录 v66 首期与当时的实机结论；v68 已扩展配置和增加主题接收，当前行为见 [FX11_ULTRA_V68.md](FX11_ULTRA_V68.md)。

开发基线：`fx11-recovered-20261027`。LIVI 协议参考固定为
`3f86ad72049aa52d2969f083812ea37c80b65ea6`。

## 范围

新增默认关闭的「CarPlay 空调开关（实验性）」，位于选择博越 L 后的高级设置 → 博越 L 仪表显示。
开关保存后提示重新连接，由用户选择重连时机，不主动断开当前会话。
首期只发布空调总电源的 CAF characteristic（IID 514，type 0x31000002，bool）。
没有温度、风量、A/C、座椅或车窗的写入入口。

独立偏好 `boyue_l.ultra_climate_power` 不复用旧 `ultra` 开关，也不受 v36 清理旧实验选项的迁移影响。
只有明确选择博越 L 并启用该选项才声明新协议；关闭时沿用原连接路径。
普通 CarPlay 的主屏、音频、HID、地图副屏声明保持不变；现有仪表样式、绘制、原厂叠加及方控逻辑保留。
本期不发送 RequestLayout，不加载或替换 OEM Ultra 主题。

## 线格式与运行时

- 空调独立模式不声明 UI Sync（与 LIVI 无 theme_displays 时一致），避免协商未提供的原生主题布局。现有 UI Sync 编解码保留：OPACK 外层 `data` → bplist 头 → bplist 命令，数值 type/version；处理 RESET、ResetComplete、ACK 和焦点请求。重复报文复用回复，不重复推进序号。
- RCS：补充 asyn/cmnd 输出及 sync/rply 运输回执；保留原始 64 位 reply token，运输回执不进入应用层。普通 raw iAP comm 默认编码保留。
- CAF：新路径直接传输 OPACK pluginID/pluginData，不套旧 bplist params 层。原有 CAF 封装 API 保留供旧调用方使用。
- `/info` 给出最小 climate 描述；configRequest 时刷新真实车辆反馈，只有读取成功才在 configResponse 附带 initialValue，不伪造未知状态。
- 两个 CAF lane 共用一个车辆后端、串行工作线程和写入事务缓存；订阅按通道独立保存。
- 只接受 IID 514 的 Boolean 写入。重复事务复用结果；同一事务不同内容拒绝。事务缓存保留最近 128 项，断开全部通道后清理。
- ECARX：复用已验证的 `ICarFunction.setFunctionValue(0x10010100, Integer.MIN_VALUE, 0/1)`。写入前检查 active 和会话有效性；每个请求最多写一次，不自动重发。
- 只有 setter 接受且紧接着的实际回读与目标一致才返回成功。暂未生效或读取失败返回非零错误；后续只读轮询仍能回传迟到的真实变化。
- 500 ms 只读轮询同步原厂空调面板的变化。未知/失败用 unavailable 或 null 失效通知，不变成 false，不继续伪报旧状态。
- 后台连接超时 8 秒；协议回调不等待车辆 Binder。待处理请求有上限，通道关闭后排队任务不能继续下发写操作。最后一个 CAF 通道关闭会释放后端和定时任务。

## 验证方式

`LiviClimateTest` 使用上游 Rust 编码器生成的独立二进制 fixture，覆盖握手、原始 CAF 报文、双通道订阅、外部变化、失效通知、重复/冲突事务、失败回读、无效值和加密 socket 到模拟车辆后端的闭环。
另有 sync 回执 64 位 token 测试；FX11 后端测试核对实际反射调用的 function ID、区域及参数类型。
配置回归测试逐项比较普通 CarPlay 与启用空调后的 info，验证显示/音频/HID 内容不变，并覆盖全部已有仪表样式。
设置测试生成紧凑中文、宽屏英文、阿拉伯语 RTL 大字体截图。

这些是软件测试，不是 iPhone 或车辆已验收的证据。已有 Demo 的成功记录证明车辆执行接口，不能替代此次新 APK 的端到端验证。

## 实车验收步骤

1. 使用带原有本地认证资源、相同应用签名的测试 APK 覆盖安装，先保持新选项关闭，确认普通 CarPlay、音乐、导航和当前仪表工作。
2. 启用「CarPlay 空调开关（实验性）」并手动重连。确认日志中的 `Ultra negotiation` 包含手机实际提议并启用的 vehicleStateProtocol；空调独立模式 uiSyncEnabled=false。
3. 确认 CAF 通道已打开、`ECARX power feedback` 为真实状态；UI Sync 若协商，确认进入 ready。手机是否呈现空调入口需实际观察，不能用弹出 Ultra 提示替代此步骤。
4. 在车辆静止时，从 CarPlay 空调界面操作总开关，对照原厂界面和实际出风；每次请求只应触发一次 setter，`CAF power write completed success=true` 仅表示回读已确认。
5. 从原厂空调面板修改总开关，检查 CarPlay 的状态同步；断开、重新连接后重新读取当前值，不恢复旧订阅和事务。
6. 关闭实验选项并重连，复查普通 CarPlay 和现有仪表。确认协议禁用后不建立空调控制通道。

若没有空调入口，先检查 `/info`、协商和 CAF 请求是否到达；若有入口但操作失败，检查 ECARX 支持状态、setter 和回读。当前最小 schema 是否被实际 iOS 接受仍需这一步验证，不通过猜测增加未实现的车控功能。

诊断记录只描述状态和失败类型，不输出认证资源。完整 OEM Ultra 仪表与主题不属于本次验收。

## v66 实机排查（2026-10-31）

iPhone 18,2 / iOS 27.2 已打开双 CAF 通道并请求 configRequest，真实电源回读成功；消息载体兼容和 initialValue 修正后，尚未观察到 registerRequest/readRequest，空调入口未验收。空 uiSyncInfo 本身不能证明故障，也不应通过请求仪表布局替代空调入口。下一次对比移除无 OEM 主题的 UI Sync 声明，保留普通地图副屏，需实机确认。

取消 UI Sync 后用户仍反馈无入口，日志确认 uiSyncEnabled=false，仍仅有 configRequest。继续对照发现 LIVI 在车辆协议下总会补充 displayPanels。现补充实际主屏/副屏到现有视频 UUID 的映射，不发布未实现的虚拟 OEM 插件面板；手机是否接受仍待验证。

实测 displayPanels 版仍无入口。撤回此前跟随 asyn/comm 请求类型回复的兼容尝试，恢复 LIVI 的 asyn/cmnd 回复（保留旧 kind=0 路径），新增真实载体组合的加密 socket 回归与发送完成/失败摘要日志。仍待实机确认，不视为已修复。

### 11:07 手机侧补充证据
用户确认重连后仍选中 CarPlay Ultra，仅界面类似普通 CarPlay。手机日志记录切换到 Ultra 和 disabled features 归零，不能据此证明整套原生 UI 已启用；也不能认定偏好自动退回普通模式。手机接入 Mac 时车机 ADB 已断开，同次切换后的车机日志尚缺。已保存诊断与 v66 对比包；用户暂不方便恢复 ADB，停止现场测试。空调入口仍未修复。
