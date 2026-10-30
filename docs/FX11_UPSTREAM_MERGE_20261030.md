# FX11 — DiPlay 0.2.22 上游合并（2026-10-30）

本次将 `shihabal3amri/DiPlay` 的 `upstream/main` 合入本地 `fx11-recovered-20261027`。

- 合并前 HEAD：`be74c435a2f611e6963c93426623099f44808610`。
- 上游目标：`615b32ce`（包含 v0.2.22）。
- 合并提交：`9cd563e1`。
- 测试包：`0.2.22-hud-test-v60-upstream`，versionCode `91`，包名仍为 `com.shihab.diplay.hudtest`。

## 合入内容

- 麦克风：iPhone 未指定 framesPerPacket 时，根据采样率和声道数选择 20/10/5 ms 的 PCM 包，避免超过链路 MTU；48 kHz 单声道改为 10 ms。
- AirPlay：视频、事件、iAP 和设置通道校验连接来源；时钟响应只接收当前控制会话对应的设备；丢弃过短的视频加密帧。
- 土耳其语、网站语言版本、0.2.22 发布与兼容性文档。
- 补齐上游土耳其语中缺失的 128 条 FX11 自定义设置文案，保留参数占位符。

## 冲突与本地修改

README 的三个语言版本保留“专为 FX11”的定位及本分支链接，更新上游发布链接。
版本号采用本地递增的 91，保留测试版包名、认证资源加载和独立安装包校验逻辑。

合并前备份了 56 个已修改或未跟踪文件；恢复后，除有意更新的 `mobile/build.gradle.kts` 外，另外 55 个文件与备份的 SHA-256 一致。
包括 v59 仪表布局、高光、FX11 数据与按键接入，以及原有未提交的空调功能工作。此次没有重写经典仪表、改变仪表功能或替换本地认证资源。
原有未提交的工作继续保留在工作区，不将它们混入上游合并提交。

## 恢复点

- 本地备份分支：`backup/fx11-before-upstream-20261030`。
- 保留的 stash 对象：`00233987d74d8fc882fe962d56074f9ec05a5f16`（含未跟踪文件）。
- 工作区文件压缩包、补丁、校验清单和构建日志：`/Users/meow.zang/Documents/Codex/DiPlay-upstream-merge-20261030/`。

## 验证

运行仓库 AGENTS.md 要求的 shared/common/home 单元测试、mobile/home/maphost Lint 和构建，并额外运行 `:mobile:assembleStandaloneDebug`。
完整结果见上述输出目录中的 `verification.json`；安装包认证资源与 v59 对比，签名验证后方可交付。
本次没有实车测试，也没有推送 GitHub。

验证结果：3,963 项测试通过、1 项跳过、0 失败；Lint 和全部构建成功（4m 33s）。26 张仪表回归图与 v59 逐字节一致。APK 认证资源及签名与 v59 一致。
