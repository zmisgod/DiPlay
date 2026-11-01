# DiPlay ESP32 伴随屏 v0.3.3

适配 Waveshare ESP32-S3-Touch-AMOLED-1.75C（466×466、ESP32-S3、8 MB PSRAM、32 MB Flash）。
支持 Wi-Fi 或 USB 数据线。当前有导航引导、音乐和仪表导航地图三页；ESP32 接收 JPEG，不解码 H.264 视频，也不传送音频。

## 优先使用 USB 数据线（无需占用车机 Wi-Fi）

1. 使用支持数据传输的 USB 线，将 ESP32 接到车机的 USB Host 数据口；仅供电口或充电线不能传输画面。
2. DiPlay → 高级 → ESP32 副屏（实验性）→ 连接方式选择 USB；点击“连接 / 授权 USB”，允许系统弹出的设备访问请求。
3. 配对码填 `12345689` 并启用。USB 模式忽略 IP 地址。v64 增加专用 USB 插入识别入口：已启用 USB 副屏时重新插入自动恢复；首次仍需系统授权，可在系统 USB 选择框中设为默认。没有设为 USB/没有启用时只打开设置，不自动改变 CarPlay 连接。权限拒绝后不会循环弹框，仍可用设置中的授权按钮重试。
4. 首次收到正确配对码的 USB 请求后，固件关闭 **ESP32 自己的 Wi-Fi**，并保存 USB 模式，重启仍不启动无线电。Android 不更改车机 Wi-Fi、热点或网络路由。
5. 若要恢复无线，先停用 Android 的 USB 传输，再长按 ESP32 屏幕启动 Wi-Fi，在 DiPlay 切回 Wi-Fi。已有 Wi-Fi 配置保留。

USB 使用 S3 原生 USB Serial/JTAG CDC，Android 使用 UsbManager 用户授权和 bulk 端点；不会占用 iPhone 的 USB 接口。当前 VID/PID 为 `303a:1001`，未支持其它 USB 转串口芯片。
参考：[Espressif USB Serial/JTAG](https://docs.espressif.com/projects/esp-idf/en/release-v5.5/esp32s3/api-guides/usb-serial-jtag-console.html)、[Android USB Host](https://developer.android.com/develop/connectivity/usb/host)。

## Wi-Fi 配网与 DiPlay 设置

1. USB 供电后，设备显示 `DiPlay-ESP32-xxxx` 热点和配对码；热点密码固定为 **12345689**，配对码也固定为 **12345689**。
2. 手机连接该热点，访问 **http://192.168.77.1:8080**。输入屏上的配对码，以及目标 2.4 GHz Wi-Fi 的名称、密码。
3. 目标 Wi-Fi 必须让车机与 ESP32 相互访问。设备连上后屏上显示新 IP；在 DiPlay 的 **设置 → 高级 → ESP32 副屏（实验性）** 填入该 IP（不含 http://、端口）和配对码，再启用。
4. 可点击“导航信息”、“音乐信息”或“仪表导航地图”选页；设备左右滑动也能切换三页。
   “全页面 HUD 方向”提供正常、上下镜像（HUD 反射）、左右镜像、旋转 180°，立即生效，统一作用于导航、音乐和地图三页，包含封面及页码指示；不改变车机主屏或原车仪表方向。
5. 在 **车辆 → 方向盘按键** 将一个可配置按键的短按或长按设为 **切换 ESP32 页面**。例如保留 MODE 短按原功能，另将 MODE 长按绑定该动作。不会自动覆盖已有绑定。

配网热点使用 192.168.77.1，以减少与常见车机热点网段的冲突。后续仍可连接配网热点修改 Wi-Fi；配对码保存在 NVS，重启保留；v0.3.0 会把旧随机配对码迁移为 12345689，并保留已有热点名称及目标 Wi-Fi 配置。禁用 Android 开关停止传输，ESP32 6 秒后隐藏旧画面。

ESP32-S3 只能连接 2.4 GHz Wi-Fi。仅有 5 GHz 的 CarPlay 热点不能直接供 ESP32 使用；还需车机可达的 2.4 GHz 网络，或车机支持的双频/并发网络配置。此功能不会自动修改车机 Wi-Fi、热点或 CarPlay 的连接设置。当前没有完成真实车机的无线网络兼容性验证。

## 页面与数据

- 导航：道路名、转向箭头、下一动作距离、剩余距离/时间；小圆显示 FX11 实际车速，缺失显示 `—`。没有路线显示等待导航。
- 音乐：封面、歌名、歌手、播放进度。使用 DiPlay 已有 CarPlay 元数据和封面缓存的独立副本。
- 地图：复用已解码的仪表导航副屏（stream 111），按原始比例提取中央正方形，裁去两侧再填满圆屏，不包含左右仪表圆盘。地图页取消底部分页点；连续近黑的视频边缘留白会先裁掉，内部暗路面及全黑空帧不会触发裁切。USB 模式目标最高约 4 帧/秒，Wi-Fi 约 1 帧/秒；仪表导航流或 Surface 不可用时显示等待地图，不退回主屏截图，不创建额外解码器。需要当前会话已提供仪表导航流。
- 由 Android 渲染中文与导航资源为 466×466 JPEG，音乐/文字页约每秒刷新，USB 地图按 250 ms 周期目标调度，至少留 40 ms 空隙；慢链路自动降速，始终只有一帧在途。不会在音频线程编码或排队积压视频帧。
- 网络失败间隔 3 秒重试；页面 revision 防止切页后显示上一页的在途图片。已接受的连续按键逐条发送并确认，短时重试由固件按 client/id 去重。
- 空导航、断流不会生成假的导航/车况。现有主屏 CarPlay、仪表样式和原来的按键功能保持各自独立。

## 构建

ESP-IDF **v5.5.1**，`idf.py build`，`idf.py -p <串口> flash`。首次构建通过组件管理器下载 BSP 与 LVGL，`dependencies.lock` 固定依赖。

```sh
idf.py build
idf.py -p /dev/cu.usbmodem2101 flash
python3 -m unittest discover -s tests -p '*_test.py'
```

屏幕、触摸和引脚使用 Waveshare 官方 BSP `waveshare/esp32_s3_touch_amoled_1_75c`；依赖 LVGL 9.5.0，开启 TJPGD、MEMFS 和 C 分配器，使用 PSRAM。
官方硬件参考：https://github.com/waveshareteam/ESP32-S3-Touch-AMOLED-1.75C 。BSP、LVGL 和 ESP-IDF 遵循各自组件内的许可证；本目录没有从商业 AutoDisplay APK 移植固件代码。

## 局域网协议 v2

端口 8080。除配置网页 GET `/` 外均要求 `X-DiPlay-Key`。

- GET `/state` → `{protocol:2,pages:3,page:0|1|2,revision,width:466,height:466}`。
- POST `/page`，JSON `{page:-1|0|1|2,id:整数,client:UUID}`；-1 为循环切页，0 导航，1 音乐，2 仪表导航地图。返回 state。最近一次同 client/id 重传不重复切页；断电后该去重记录不保留。
- POST `/frame?revision=N`，`image/jpeg`，最大 128 KiB，仅 baseline JPEG、466×466；旧 revision 返回 409。
- POST `/wifi`，JSON `{ssid,password}`，保存后重连。敏感信息不打印到串口。

v63 的 USB 功能需要 v0.3.0 固件；Wi-Fi 兼容原协议 v2。连接旧固件会提示升级，不错误确认尚未支持的第三页命令。升级不会清除 NVS 中已保存的 Wi-Fi；v0.3.0 将配对码统一为 12345689，需在 DiPlay 设置中手动更新一次配对码。

这是配对局域网 HTTP 协议，未提供互联网访问和 TLS。照片和 Wi-Fi/配对信息仅用于所连接的设备。

## USB 帧格式

请求：`DPL3` + seq/headerLength/bodyLength（各 uint32 little endian）+ UTF-8 JSON `{key,path}` + body。
header 最大 1024 字节，body 最大 128 KiB。路径 `/state`、`/page`、`/frame?revision=N` 与 HTTP 对应，USB 不提供配网写入接口。
回复：`DPR3` + seq/status/bodyLength（各 uint32 little endian）+ body。Android 限制回复 4096 字节，处理拆包、启动噪声、旧序号；非 200 不确认命令。
固件串口日志改走 UART，USB CDC 专用于数据；无数据 6 秒隐藏旧画面。

## v0.3.1 绘图优化与测量

旧路径把 JPEG 作为 RAW 图片交给 LVGL；BSP 使用 50 行部分绘制缓冲，TJPGD 在重复请求区域时会重启解码。现在先在请求工作线程中完整解码一次，转换为 PSRAM 中的 RGB565，再短暂持有显示锁换图，避免多次解码同一帧。保留长度、格式、尺寸和页面 revision 校验；错误图片不能覆盖上一张有效图。

电脑 USB → 连接的实体 ESP32，同一份 25,089 字节 JPEG 连续 30 帧：旧版确认耗时中位数 1047.81 ms；新版 175.50 ms，P95 175.79 ms。这测的是发送到固件接收/解码/提交图片的时间，不包含 Android PixelCopy，也不是通过摄像机测量的面板帧率，不能当作实车帧率保证。

## 蓝牙边界

本版未实现蓝牙传输。ESP32-S3 仅有 BLE，没有经典蓝牙 SPP。官方在屏蔽箱、MTU 512、7.5 ms interval、DLE 251 的双开发板测试中，S3 写入吞吐约 753 kbps（1M PHY）或 1.34 Mbps（2M PHY）。约 25 KB 图片仅传输的理想估算为 0.15–0.27 秒，另需协议、解码、显示；实际车机参数和无线环境可能更慢。
歌名、导航指令等小数据适合 BLE，连续图片优先 USB；本次不增加未经车机验证的 BLE 协议栈。
参考：[S3 BLE 支持](https://docs.espressif.com/projects/esp-idf/en/v5.2/esp32s3/api-guides/bluetooth.html)、[Espressif BLE 吞吐测量](https://github.com/espressif/esp-idf/blob/master/examples/bluetooth/nimble/throughput_app/README.md)。

## 验证范围

本地 Android 单元测试/渲染截图/HTTP 传输测试、编译与 Lint；固件编译、JPEG 边界测试、USB 烧录和串口启动检查。最终车机 USB Host 授权、车机 → 实体屏的真实内容刷新、方向盘实际动作、触摸和长时间稳定性仍需装车验证；未将模拟截图当作实机证据。

## v0.3.2 页序

配合 APK v65：左滑与方向盘下一页均按导航 → 地图 → 音乐循环；右滑反向。
保持协议 2 及原页面 ID：0 导航、1 音乐、2 地图；保存的直接选页命令仍有效。
导航/音乐概念布局和时间文字在 Android 端渲染，固件继续接收完整 466×466 JPEG；保留 v0.3.1 的单次解码与 USB/Wi-Fi/HUD 能力。

## v0.3.3 色彩修正

LVGL 9.5.0 内置 TJPGD 实际输出 BGR888；v0.3.1/v0.3.2 的单次解码回调误按 RGB888 转换，导致红蓝互换。现按 BGR 转为原生 RGB565，不更改面板方向、字节交换或地图饱和度。适用于地图、封面和所有传输页面。
配合 APK v67：音乐页歌名、歌手、时长、已播放进度及选中页点统一白色；未播放轨道保留半透明白色以区分进度。封面保留原色。

`jpeg_pipeline_test.py` 用项目锁定的真实 TJPGD 源码解码标准 JPEG 色块（4:4:4 与 4:2:0），验证红绿蓝、浅蓝和灰白色；需要本机 Pillow 与 C 编译器。只更新 APK 不能修复旧固件的红蓝颠倒，需同步刷入 v0.3.3。升级不清除 NVS 配网与配对信息。
