# ESP32 音乐 / 导航伴随屏

## v67 色彩修正与白色音乐信息

配套固件 v0.3.3 修正 LVGL TJPGD 的 BGR888→RGB565 转换：旧代码将红蓝互换，导致红色终点变蓝、蓝色地图符号变橙。用真实解码器验证 4:4:4/4:2:0 JPEG 色块，覆盖主色、浅蓝、灰白；旧代码复现失败，新代码通过。
APK v67 的音乐歌名、歌手、已播放/总时长、进度线、进度点与选中页点统一白色，未播放轨道使用半透明白色；封面保留原色。地图全屏、HUD、页序、方控和 USB/Wi-Fi 保持。修正颜色必须更新固件，只有 APK 更新无法修正旧固件。尚待此次实体屏验证。


## v65 概念页还原

- 导航：金属细边、大转向箭头、独立车速圈、居中的距离，以及剩余里程/时间。
- 音乐：192×192 圆角封面、歌曲/歌手、青色进度、白色进度点；两端展示已播放和总时长，缺失显示 `--:--`。真实时间来自 iPhone 元数据，不编造进度。
- 地图仍复用仪表导航流中央区域，全屏、无边框/分页点；USB 地图最高目标 4 fps。
- 浏览顺序改成导航 → 地图 → 音乐，反向滑动顺序相反；方向盘下一页与触屏一致。线协议 ID 保留 NAV=0、MUSIC=1、MAP=2，设置与历史待发命令不会串页。
- 需要配套固件 v0.3.2。导航/音乐页底部圆点也按新顺序展示；HUD 依旧在整帧完成后镜像。


实现、配网、构建和协议见 [固件 README](../firmware/esp32-companion/README.md)。

Android 新增 `Esp32Settings`、`Esp32DisplayService`、`Esp32HttpClient`、`Esp32UsbClient`、`Esp32FrameRenderer`、`Esp32CarPlayFrames`，默认关闭；设置位于高级，方向盘动作独立于已有仪表翻页。服务独立持有 FX11 按键监听和 `NativeDashboardRuntime` 订阅，关闭时释放，不停止其它所有者。仪表导航流在可用时通过 PixelCopy 按需取样并裁取中央正方形，不建立额外解码器。HUD 镜像统一修改 ESP32 导航、音乐和地图三页，不改变仪表绘制、音频或 CarPlay 认证实现。

测试：`Esp32DisplayTest`、`Esp32TransportTest`、`GeelyDashboardWheelTest` 新增独立所有权用例，以及 `AdaptiveSettingsUiTest` 的 ESP32 中英/阿拉伯语大字体截图。APK 仍通过既有 `DIPLAY_AUTH_ASSETS_DIR` 和 `:mobile:assembleStandaloneDebug` 本地打包流程携带认证，不将认证文件加入源码。

## v64

- `Esp32UsbAttachActivity` 只匹配真实枚举的 `303a:1001`，与 iPhone USB 入口分开；恢复传输需要之前已经启用 USB 副屏。运行中的服务监听插拔，在每次连接中最多自动请求一次权限，拒绝不会反复弹窗。
- 地图只占一张全屏位图，不再绘制底部分页点。`contentCrop` 先检测连续近黑边缘（单边小于 1/3，最少 4 像素），再从有效内容中裁出中央正方形。内部暗区域、全黑帧和不确定的大块黑区不作为黑边处理。
- USB 地图最多约 4 fps，单工作队列且至少 40 ms 间隙，Wi-Fi 与文字页保持约 1 fps。
- 固件 0.3.1 把 JPEG 一次性解码为 RGB565 后显示，实体 USB 同图测量约 1048 ms → 176 ms；这不是实车端到端 FPS。
