# v0.4.0 验证记录

日期：2026-10-05（Asia/Shanghai）。版本 **0.4.0 / versionCode 4**，在同一 GitHub 项目的 0.3.0 上继续修改，没有重建项目。

环境：Temurin JDK 17.0.20.1、Gradle 8.13、Android Platform 36 r02、Build Tools 35.0.0。

```text
./gradlew build testDebugUnitTest lintDebug assembleDebugAndroidTest --console=plain
BUILD SUCCESSFUL in 40s
138 actionable tasks: 53 executed, 85 up-to-date
```

## 单元测试与 Lint

Debug / Release 各运行同一组 **17 个单元测试**：PackRulesTest 9 个、ZipNamingTest 5 个、ResolutionModeTest 3 个。均 0 失败、0 错误、0 跳过，不是 34 个不同测试。

- 缺省 HEIC 85、JPEG 95；Quality 快捷值仍为 50 / 75 / 85 / 90 / 95 / 100。
- 原始模式直接保持输入尺寸，忽略 25 / 33 / 50 / 60 / 67 / 75 / 100 等保存的降低比例；1440×19399 仍均匀分成两片。
- 降低模式 50% 输出 720×9700，不切片；67% 输出 965×12997；100% 保持原尺寸。模式切换保留质量、格式和比例。
- 原有双轴切片、命名/重名、图片专用 STORED ZIP、size/压缩大小/CRC32、10 MiB 双遍有界流、流关闭、输入 CRC 变化、取消清理、ZIP 名称/日期/非法字符/长度测试均继续通过。

Android Lint：**No issues found**。保持 abortOnError / warningsAsErrors，不使用 baseline，仅保留原有两类依赖/工具链更新建议豁免。报告及 6 份测试 XML 位于 verification。

## 设置兼容与分享流程

- 分享小窗包含 ZIP 名称、日期开关及原始/降低分辨率两个单选模式。初次没有模式键时默认原始分辨率；原有比例值继续保留，但仅降低模式使用。
- 名称、日期、模式在确认时通过 DataStore 原子保存；只更新三个相关键，不覆盖保存的质量、比例、格式或其他高级键。取消不覆盖上次确认的偏好。
- HEIC 85 仅用于新安装或质量偏好缺失；已有质量值，包括已保存的 95，继续保留，不以新默认强制覆盖。JPEG 默认 95 不变。
- 主界面比例标为“降低分辨率比例”，也提供模式选择；已打开的主界面同步确认后的模式。结果显示实际生效比例。
- 确认时读取最新保存的图像设置，并覆盖本次模式；原始尺寸直接交给切片/解码阶段，没有缩放。内存预算仍保留，超预算提示选择降低模式及调低比例。
- 保留独立透明 ShareActivity，成功后提示并 finish 自己，不打开主界面或系统 Sharesheet；桌面图标仍进 MainActivity，手动结果分享保留。

## APK 与签名

- 可安装附件：GPT-Screenshot-Pack-0.4.0-debug.apk。项目无 release signing，继续使用原 debug keystore，v2 签名验证通过。
- 证书 SHA-256 与 0.1.0 / 0.2.0 / 0.3.0 一致：e6026d6cf3d969afe91ca9a266d879d379ad8299dda863190a2c5c3fa082d354，可覆盖安装；密钥未上传。
- aapt：com.gptscreenshotpack、versionCode=4、versionName=0.4.0、minSdk=28、targetSdk=36。
- 无 INTERNET / MANAGE_EXTERNAL_STORAGE；WRITE_EXTERNAL_STORAGE 仅 maxSdkVersion=28。MAIN/LAUNCHER 仍只由 MainActivity 接收，SEND / SEND_MULTIPLE 的 image/* 仍只由 ShareActivity 接收。

## Android 设备测试边界

当前环境没有 Android 实机或可用模拟器，**6 个设备测试已编译但未执行**，不能把编译结果视为实机通过。

- 保留实际 HEIC 往返、PNG/50 张/长图/切片/失败、公共 Output/pending/重名、Temp 清理保护活跃任务、无网络权限及独立分享路由。
- 分享流程测试已扩展为：保存的降低比例 50%，初次仍为原始；选择降低模式后旋转保持模式/名称/日期，ZIP 图片真实尺寸 8×12；下次记住降低模式，切回原始后输出真实 16×24；两种质量 75/90、比例和 PNG 格式保持；保存后自动关闭，不启动 MainActivity；全部失败保持错误与重试。
- 测试恢复原图像/命名/模式偏好，清理自己的测试输出，不调用全量 Output 清理。

仍需实机验收：真实相册分享及返回、软键盘和小窗外观、Android 9 授权、文件选择器可见性、厂商 HEIC 行为以及本次 85 质量的实际体积/OCR 对照。没有用本地单元测试声称实测压缩率或识别速度。连接设备后运行 ./gradlew connectedDebugAndroidTest。

## 输出与发布

最终 ZIP 继续保存到 Download/GPT Screenshot Pack/Output/；中间文件在 Temp/<UUID>/。Output 永不自动删除；ZIP 仍仅图片、STORED，图片名/切片编号/重名规则与清理逻辑保留。HeifWriter 编码、CQ Auto、Grid Auto、MediaCodec 诊断未修改，稳定公开 API 差异继续记录在 README。

提交源码、规格、README、验证记录，创建 v0.4.0 tag / GitHub Release，附件为同签名 debug APK、源码 ZIP 和 SHA-256 文件。使用现有发布工作流校验并上传已验证的同一批文件，不重新签名或上传密钥。
