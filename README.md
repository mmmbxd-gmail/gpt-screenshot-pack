# GPT Screenshot Pack

Android 本地截图预处理工具，Kotlin + Compose，Android 9（API 28）以上，目标 Android 16（API 36）。默认：HEIC、50%、质量 95、编码器/CQ/Grid 自动。无网络权限，不修改原始图片。

## 下载 APK

[下载 v0.1.0 APK](https://github.com/mmmbxd-gmail/gpt-screenshot-pack/releases/download/v0.1.0/GPT-Screenshot-Pack-0.1.0-debug.apk) · [Release 页面](https://github.com/mmmbxd-gmail/gpt-screenshot-pack/releases/tag/v0.1.0)

APK 使用开发签名，适合个人安装测试。Release 同时附源码 ZIP 和 SHA256SUMS.txt。源码、构建验证和公开 API 差异见下文；实机验证尚未完成。

## 使用

- 相册按顺序选择图片 → Android 分享菜单 → GPT Screenshot Pack → 自动使用保存设置处理 → 系统 Sharesheet 分享 ZIP。
- 桌面入口可调整 25%～100% 缩放、HEIC/PNG/JPEG、独立保存 HEIC/JPEG 质量，选择一张或多张图片测试，查看结果并手动分享。
- HEIC 失败（含输出无法再次解码）时自动回退 PNG；结果页显示实际回退文件和原因。单张失败跳过，全部失败不生成 ZIP。
- 最终任一边超过 16384 px 时均匀切片，超限边的片尺寸目标 ≤12000 px。双边超限采用从上到下、从左到右的网格顺序。正常长截图 1440×19399 在 50% 时为 720×9700，不切片。
- 文件保留名称主体和时间，增加 `resized_`，切片 `_1`、`_2`，重名 `_copy2`、`_copy3`。ZIP 只含处理后的图片，按输入和切片顺序写入，无辅助文本。
- ZIP 使用 `GPT_Screenshots_yyyyMMdd_HHmmss.zip`，在独立缓存任务目录中避免同秒覆盖。使用 FileProvider 的 `content://` 和临时读取授权分享。
- 启动时清理超过 24 小时的任务缓存，可手动立即清理。清理使旧分享链接失效，不触碰原文件。

## 公开 API 核查与规格差异

核查日期：2026-10-04。依据官方文档、Google Maven metadata 及对应版本 sources.jar；没有使用反射、私有 API、codec hack 或自写 HEVC 编码器。

| 规格假设 | 核查结果与第一版实现 |
| --- | --- |
| HeifWriter 稳定版可用 EncoderPreference | [发布页](https://developer.android.com/jetpack/androidx/releases/heifwriter)及 [Maven metadata](https://dl.google.com/android/maven2/androidx/heifwriter/heifwriter/maven-metadata.xml)报告稳定版 **1.1.0**，最新预发布版 **1.2.0-beta01**（2026-09-23）。1.1.0 发布源码没有 EncoderPreference 或 setEncoderPreference。第一版固定使用 1.1.0，隐藏硬件/软件偏好与 CQ 偏好设置，显示 Auto。 |
| EncoderPreference 可以配置所有 CQ 策略 | [EncoderPreference](https://developer.android.com/reference/androidx/heifwriter/EncoderPreference)及其 [Builder](https://developer.android.com/reference/androidx/heifwriter/EncoderPreference.Builder)在 1.2.0 预发布版中提供无偏好、硬件/软件 Only/Preferred，以及 `CONSTANT_QUALITY_MODE_PREFERRED` / `CONSTANT_QUALITY_MODE_ONLY`。它没有 Disable CQ。Only 也不等价于规格中允许 fallback 的 Prefer CQ。即使以后升级稳定版，也需重新检查语义，不能伪造 Disable CQ。 |
| Auto CQ 必须可控 | 1.1.0 源码在 HEVC fallback 选择时倾向 CQ；配置阶段若支持 CQ 则使用 CQ，否则支持 CBR 时选 CBR，再退到 VBR。由 AndroidX 控制，应用不设 bitrate mode，不保证某候选最终被选中。CQ 指 **Constant Quality**，不是对应用开放自定义 QP。 |
| 可显示当前实际 codec、硬件加速、bitrate mode | [HeifWriter](https://developer.android.com/reference/androidx/heifwriter/HeifWriter)没有公开内部 MediaCodec / 已选 codec / bitrate mode getter。第一版三项均为 **Unknown**。候选能力页不代表实际运行选择。没有另建探测 codec 来冒充实际编码器。 |
| 设备能力可查询 | [MediaCodecInfo](https://developer.android.com/reference/android/media/MediaCodecInfo) / [EncoderCapabilities](https://developer.android.com/reference/android/media/MediaCodecInfo.EncoderCapabilities)支持 CQ/CBR/VBR、质量/复杂度范围，VideoCapabilities 支持宽高范围。硬件/软件/vendor 标志 API 29 起可查，API 28 显示 Unknown。SoC 用 API 31 起的 Build.SOC_MANUFACTURER / SOC_MODEL，不按机型推测。设备报告能力不保证每个尺寸或格式能实际编码。 |
| ImageDecoder 可按目标尺寸解码及切片 | [ImageDecoder](https://developer.android.com/reference/android/graphics/ImageDecoder) API 28 支持 `setTargetSize`、软件分配、目标 sRGB、缩放后的 `setCrop`。逐片请求裁切输出，不先创建完整原尺寸 Bitmap；读取 header 后直接中止该次解码。setCrop 不承诺底层只分配区域大小，因此还按完整缩放像素数实施保守内存预算。 |
| 任意大图都能处理 | 每次仅持有一片，目标总像素 ARGB 估算预算为 `min(192 MiB, Java heap 上限 / 3)`；超过预算时跳过并提示降低缩放，绝不偷偷降低比例或增加正常图切片。异常巨图不保证能被设备解码，预算不能涵盖所有厂商 native/GPU 分配。 |
| Grid 为自动 | [HeifWriter.Builder](https://developer.android.com/reference/androidx/heifwriter/HeifWriter.Builder) grid 默认启用，tile 尺寸由 AndroidX 选择。第一版保持默认，不提供 Grid 开关；HEIF 内部 tile 与应用生成的独立切片文件不同。 |
| PNG 8 bit True Color | 解码请求 sRGB 软件可变 Bitmap，必要时转换 ARGB_8888，使用 Android Bitmap PNG 编码器；不使用 palette、低内存 RGB565 或量化。设备测试检查 IHDR 为 8 bit、RGB/RGBA。PNG 无质量 UI。 |
| 透明/HDR 图片 | 第一版统一为 SDR sRGB 8 bit；HEIC/JPEG 将透明区域铺白，PNG 正常模式保留 alpha。HEIC 失败后的 PNG 继承铺白结果。不保留 HDR/gainmap/EXIF 元数据，方向交由 ImageDecoder 处理。截图文字质量仍需目标设备 OCR 对照。 |
| 文件名完全原样保留 | 保留中文、数字、时间等合法字符，仅清理路径分隔符/控制字符以避免路径穿越。超长名称主体（UTF-8 >210 字节）跳过并报错，不静默截断。提供方未给名称时用 `image_输入位置`，不可能恢复未知的原始名字。 |
| 分享会直接上传至 ChatGPT | 自动打开系统 Sharesheet，用户选择目标。ChatGPT 是否接受 ZIP/HEIC、是否解压、是否二次压缩不属于 Android 应用能够保证的能力。没有自动上传或指定第三方私有组件。 |
| 任务后台常驻 | 第一版在 ViewModel 中运行，旋转屏幕不重启任务，后台完成后回前台才触发分享。取消在逐片/ZIP 检查点响应；系统同步解码与 HEIC stop 无法瞬间中断，HEIC stop 有 30 秒上限。未实现前台服务/进程死亡恢复，系统杀进程后需重新分享，残留缓存下次过期清理。 |

1.1.0 发布源码还表明默认创建的回调 HandlerThread 未自行退出，因此应用用公开 `setHandler` 提供自己管理的线程，并在 close 后 `quitSafely` / join，避免批量处理积累线程。`stop(timeoutMs)` 文档文字含 microsec 与参数名不一致，发布源码调用 Java `wait(timeoutMs)`，本版按毫秒设 30,000。

## 工程结构与实现顺序

1. 核查真实 API 和依赖版本，确定稳定版功能边界。
2. 建立 Gradle/Kotlin/Compose 工程及无网络权限清单。
3. 实现 `core/PackRules.kt`：默认参数、缩放、切片、保名重名、流式 ZIP。
4. 实现 `PackProcessor.kt`：逐张/逐片 ImageDecoder → 编码 → 验证 → ZIP，资源释放、取消、fallback、缓存清理。
5. 实现 DataStore、ViewModel、分享接收/输出、主界面与 MediaCodec 能力诊断。
6. 运行构建、测试、lint 修复并生成 APK；实机验收单独记录，未运行的不宣称通过。

后续比例、质量、阈值、切片和命名主要修改 core；编码器选择与 CQ 待稳定版接口可用后集中修改编码层。

## 构建与测试

需要 JDK 17+、Android SDK Platform 36、Build Tools 35.0.0。依赖固定：AGP 8.13.0、Gradle 8.13、Kotlin 2.2.20、Compose BOM 2025.09.01、HeifWriter 1.1.0。设置 `ANDROID_HOME` 或本机 `local.properties` 的 sdk.dir。

```sh
./gradlew build testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

连接 Android 16 实机（启用 USB 调试）后：

```sh
./gradlew connectedDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

单元测试覆盖默认缩放/19399 px、16384 阈值、双轴切片面积/均衡/顺序、Unicode/时间命名、重名与切片名称碰撞、ZIP 顺序/内容/取消和空 ZIP 拒绝。设备测试覆盖 JPG→HEIC→重新解码→HEIC 输入，PNG 50 张、长截图与切片、PNG IHDR、部分及全部失败、源内容不变、分享过滤器、无网络权限。HEIC 设备测试要求真正生成 HEIC，PNG fallback 不算通过。

Lint 使用 `abortOnError=true` 与 `warningsAsErrors=true`，不使用 baseline。`app/lint.xml` 仅忽略 `GradleDependency` / `AndroidGradlePluginVersion` 两类“存在更新版本”的建议；这是对固定 API 36 工具链/依赖的显式选择，不关闭 API、安全、资源或代码正确性检查。较新的 Activity/Lifecycle/Core/Compose 版本存在，本版不要求追随最新依赖；HeifWriter 已特别核查并使用最新稳定版 1.1.0。Android 12+ 的云备份/设备迁移排除规则已显式配置。

## 验收状态

2026-10-04 最终执行 `./gradlew build testDebugUnitTest lintDebug assembleDebugAndroidTest`：**BUILD SUCCESSFUL**。Debug/Release 各运行同一组 5 个单元测试，均 0 失败、0 错误、0 跳过。Lint 报告 **No issues found**（上述两类版本更新建议显式豁免）。Debug 主 APK、Release 未签名 APK、Debug 设备测试 APK 均已生成；主 APK 的 v2 签名验证通过，APK 清单实查没有 INTERNET 权限。

记录见 `verification/` 和 [VALIDATION.md](VALIDATION.md)。可安装 Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`；源码交付包不包含 build 输出、SDK、本机代理或 local.properties。

当前环境无 Android 实机/可用模拟器；**设备测试只完成编译，没有执行**。硬件编码、系统 Sharesheet、50 张 HEIC、目标设备 19399 px 截图和长时间 native 内存/文件句柄观测仍需实机验收。设备测试 fixture 是合成图，不替代实际截图的 OCR 和颜色质量检查。
