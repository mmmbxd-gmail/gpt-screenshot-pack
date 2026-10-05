# v0.5.0 验证记录

日期：2026-10-05（Asia/Shanghai）。版本 **0.5.0 / versionCode 5**，在现有同一项目的 0.4.0 上小步迭代，没有重建项目。

环境：Temurin JDK 17.0.20.1、Gradle 8.13、Android Platform 36 r02、Build Tools 35.0.0。

```text
./gradlew build testDebugUnitTest lintDebug assembleDebugAndroidTest --console=plain
BUILD SUCCESSFUL in 32s
138 actionable tasks: 49 executed, 89 up-to-date
```

## 单元测试与 Lint

Debug / Release 各运行同一组 **18 个单元测试**：PackRulesTest 10 个、ZipNamingTest 5 个、ResolutionModeTest 3 个。均 0 失败、0 错误、0 跳过，不是 36 个不同测试。

- HEIC/JPEG/PNG 示例 entry 全部为 DEFLATED；按顺序仅存图片。ZipFile 和 ZipInputStream 均能还原内容，未压缩大小和 CRC 正确，输入未改写。
- BEST_SPEED 通过实际压缩结果与参考 raw DEFLATE 等级 1 比较，并确保 fixture 能与默认压缩级别区分；不是只断言常量值。
- 10 MiB 生成流仍为两遍 64 KiB 有界处理、两次打开和关闭；验证解压字节长度、压缩后大小与条目类型。
- 输入在预校验和写入之间发生 CRC 变化会拒绝；DEFLATED 可使用 data descriptor，故写入阶段显式计算实际 size/CRC 与预校验比较，不依赖旧的 STORED closeEntry 校验。取消、错误和空/非图片拒绝继续通过。
- 原有默认 HEIC 85/JPEG 95、质量与比例快捷值、原始/降低模式、16384 阈值、双轴切片、图片/ZIP 命名、日期、非法名称和 UTF-8 长度测试全部通过。

Android Lint：**No issues found**。保持 abortOnError / warningsAsErrors，不使用 baseline，仍只豁免原有两类工具链/依赖更新建议。verification 包含最终构建日志、lint 报告和 6 份单元测试 XML。

## 本次功能与设置兼容

- ZIP 全部改为 ZipEntry.DEFLATED / Deflater.BEST_SPEED（等级 1），压缩大小由写入器计算，不再强制等于原大小；只含图片，没有 manifest、README、JSON 或日志。
- 分享设置窗增加 HEIC / JPEG / PNG，确认时与名称/日期/模式一起原子保存；无记录默认 HEIC，沿用已有 format 键和保存值，不清空历史偏好。
- MainActivity 移除格式选择，长期保存方法不写 format 键，分别保留 HEIC/JPEG 品质和两组 50/75/85/90/95/100 快捷值。桌面测试读取最近确认的分享格式，主界面只显示说明。
- 当前主界面监听最后确认的模式/格式，改变长期品质/比例不会覆盖分享格式；分享保存四个相关键，不改质量/比例或其他高级键。
- 已有质量值保留，HEIC 缺省 85 / JPEG 95 不变；PNG 不新增有损质量设置。
- 原始模式强制 100%，降低模式使用主界面比例；原来的模式说明、记忆、16384 px 分割、命名和清理保持。
- 仍为独立轻量 ShareActivity，保存到公共 Output 后提示并关闭，返回原应用，不再次打开 Sharesheet。桌面入口仍进入 MainActivity。

## 可安装 APK

GPT-Screenshot-Pack-0.5.0-debug.apk，已验证 v2 签名。项目无 release signing，沿用 0.1.0～0.4.0 的 debug keystore，支持覆盖安装；密钥不上传。

证书 SHA-256：e6026d6cf3d969afe91ca9a266d879d379ad8299dda863190a2c5c3fa082d354。

aapt：com.gptscreenshotpack、versionCode=5、versionName=0.5.0、minSdk=28、targetSdk=36。无 INTERNET / MANAGE_EXTERNAL_STORAGE，WRITE_EXTERNAL_STORAGE 仅 maxSdkVersion=28；启动和分享的独立路由不变。

## 设备测试边界

当前环境没有 Android 设备或可用模拟器，**6 个设备测试已编译但未执行**，不能把编译当作实机通过。

- 原有 HEIC 实际往返、PNG/50 张/长图/失败、公共文件/清理/重名与入口测试的 ZIP 断言更新为 DEFLATED。
- 分享流程测试：默认 HEIC；主界面保存携带 JPEG 不改变任务格式；选择 PNG 后旋转保留；下次记忆 PNG/模式/名称/日期；降低输出 8×12、原始输出 16×24；改选 JPEG 使用已保存质量 90 并生成 jpg；下次记住 JPEG，取消修改 HEIC 不覆盖；全程保存后关闭，不启动 MainActivity；两种质量 75/90 和比例仍保留。
- 测试恢复原偏好，仅删除自己的测试输出，不清空用户的 Output。

真实分享返回、小窗/键盘外观、Android 9 授权、文件选择器可见性、厂商 HEIC 编码、真实截图/OCR，以及 ChatGPT/其他接收方的 ZIP 接受行为仍需实机确认。本版完成 ZIP 标准读取验证，没有声称某个第三方上传器已通过。连接设备后运行 ./gradlew connectedDebugAndroidTest。

## 输出与完整发行

最终目录：Download/GPT Screenshot Pack/Output/；中间文件：Temp/<UUID>/。Output 永不自动删除，Temp 活跃任务保护、输出清理、文件命名不变。AndroidX HeifWriter、CQ Auto、Grid Auto、MediaCodec 诊断实现未改，稳定公开 API 差异继续见 README。

提交源码、README/规格/验证记录与已签名 APK；创建 v0.5.0 tag 和 GitHub Release。附件包含 APK、干净源码 ZIP 和 SHA-256。沿用现有工作流校验并上传同一批产物，不重新签名、不开新的仓库。
