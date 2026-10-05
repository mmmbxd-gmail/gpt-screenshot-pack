# GPT Screenshot Pack

Android 本地截图预处理工具，Kotlin + Compose，Android 9（API 28）以上，目标 Android 16（API 36）。缺省：HEIC、原始分辨率、HEIC 质量 **85**、编码器/CQ/Grid 自动。JPEG 缺省质量仍为 95。无网络权限，不修改原始图片。

## 下载 APK

[下载 v0.4.0 APK](https://github.com/mmmbxd-gmail/gpt-screenshot-pack/releases/download/v0.4.0/GPT-Screenshot-Pack-0.4.0-debug.apk) · [Release 页面](https://github.com/mmmbxd-gmail/gpt-screenshot-pack/releases/tag/v0.4.0)

APK 使用开发签名，适合个人安装测试；继续使用 0.1.0 构建时的同一 debug keystore，支持覆盖安装。本项目尚无 release signing 配置。APK 作为 Release Asset 发布，同时提供源码 ZIP 和 SHA-256 校验文件。源码、构建验证和公开 API 差异见下文；实机验证尚未完成。

## 使用

- 相册按顺序选择图片 → Android 分享菜单 → GPT Screenshot Pack → **设置小窗 → 处理图片 → 生成 ZIP → 保存 → 自动关闭并返回来源 App**。继续使用独立的透明 `ShareActivity`，不进入正常主界面，也不自动打开系统 Sharesheet。
- 设置小窗包含 ZIP 名称主体、**自动附加日期时间**及两个分辨率模式。自动补 `.zip`；输入已有 `.zip`（不区分大小写）会去除再补，避免重复扩展名。日期开关默认开启，以手机本地时间在确认时生成 `yyyyMMdd_HHmmss`，例如 `聊天记录_20261005_132530.zip`。确认后通过现有 DataStore 一次保存主体、日期开关和分辨率模式，下次分享继续使用；取消不覆盖上次确认的设置。
- 首次名称主体为 `GPT_Screenshots`。空白、路径分隔符、控制字符及常见非法文件名字符会提示修改；主体最多 200 个 UTF-8 字节，保留时间和重名后缀空间。同名 ZIP 追加 `_copy2` 等，不覆盖旧文件。
- **原始分辨率**：保留更多图像细节，但 GPT 处理时可能产生更多图像分割。直接使用输入宽高，不应用保存的缩放比例；首次没有模式偏好时使用这一模式。
- **降低分辨率**：提高识别速度，减少图像分割。使用主界面的**降低分辨率比例**。原有 `scale` 偏好及 33 / 50 / 60 / 67 / 75 / 100 快捷值保留，范围仍为 25%～100%，缺省比例仍为 100%；若设为 100%，尺寸与原图相同，小窗会提示这一点。
- 桌面主界面也可选择这两个模式，手动测试遵守同一规则，并同步显示最后确认的分享模式。调整降低分辨率比例不会改变原始模式的输入尺寸。
- 桌面入口可选 HEIC/PNG/JPEG，独立保存 HEIC/JPEG 质量（范围 1～100）；新安装或质量键缺失时 HEIC 使用 **85**，JPEG 使用 **95**，快捷值仍为 **50 / 75 / 85 / 90 / 95 / 100**。PNG 不显示质量参数。升级不强制改写已保存的质量值，也不重置格式、比例或编码器/CQ/诊断；分享模式保存只更新对应的三个偏好键。
- HEIC 失败（含输出无法再次解码）时自动回退 PNG；主界面结果页显示实际回退文件和原因。单张失败跳过，有可用图片时保存 ZIP 并退出，提示成功/失败数量及 PNG 回退。全部失败不生成 ZIP，分享窗口保留错误提示，可重新命名重试或关闭。
- 最终任一边超过 16384 px 时均匀切片，超限边的片尺寸目标 ≤12000 px。双边超限采用从上到下、从左到右的网格顺序。1440×19399 在原始模式均匀分成两片，即使保存的降低比例是 50%；选择降低模式且比例为 50% 时变成 720×9700，不切片。GPT 接收后的图像分割策略不由本应用控制。
- 文件保留名称主体和时间，增加 `resized_`，切片 `_1`、`_2`，重名 `_copy2`、`_copy3`。ZIP 只含处理后的图片，按输入和切片顺序写入，无辅助文本。
- 分享 ZIP 使用确认后的名称和日期开关；桌面手动测试仍用 `GPT_Screenshots_yyyyMMdd_HHmmss.zip`。同名不覆盖旧文件；100% 时也继续保留 `resized_` 图片前缀。ZIP 每项使用 **ZipEntry.STORED**，不执行 DEFLATE，设置 size = compressedSize 和 CRC32；每张图片用 64 KiB 缓冲读两遍，不把整个文件读入内存。

## 公共 Downloads

```text
Download/GPT Screenshot Pack/
├── Temp/<任务 UUID>/      # 中间图片、尚未发布的 ZIP
└── Output/               # 完整的最终 ZIP，永不自动清理
```

- Android 10+ 使用公开 MediaStore.Downloads、RELATIVE_PATH 和 IS_PENDING。HEIC 通过 HeifWriter 的公开 FileDescriptor 构造器写入，不解析私有文件路径，不使用 DATA 列或全盘管理权限。
- 图片及 ZIP 暂存于 Temp；关闭 ZIP、校验完成且未取消后，把该 ZIP 的 RELATIVE_PATH 移到 Output，并清除 IS_PENDING。这样系统文件选择器能够找到完整文件。
- 在当前 ChatGPT 对话点击上传文件，进入 **下载 → GPT Screenshot Pack → Output** 选择 ZIP；是否接受该格式由接收方决定。分享流程保存成功后提示实际名称并关闭 Activity；桌面主界面的结果页仍显示目录/名称，保留手动“分享 ZIP”按钮。
- Android 9 保留兼容：仅在 API 28 请求 WRITE_EXTERNAL_STORAGE，完成后以不覆盖目标的移动操作放入 Output，使用限定公共目录的 FileProvider 分享。Android 10+ 不请求该存储权限。
- 成功、失败或取消时只清理当前任务的 Temp；启动时只清理超过 24 小时的本应用 Temp。Output **不参与任何自动删除**，即使已发布后才收到取消请求也保留 ZIP。
- 两个独立按钮：**清理临时文件**保留所有 Output，跳过同一进程内仍在处理的分享/主界面任务；**清理生成文件**经确认后删除本应用可访问的 Output ZIP，包含自定义名称。当前主界面任务忙时禁用清理。
- Android 10+ 清理 Output 仍按 owner、精确目录、ZIP MIME/扩展名过滤，不限旧的 `GPT_Screenshots_` 前缀；Android 9 没有 owner 列，仅清理专用 Output 目录内的 `.zip`，请勿手动放入无关 ZIP。
- 清理不访问输入 URI 的父目录，不删除原始截图。MediaStore 清理按目录和 owner 筛选；重新安装后旧文件的访问权可能需要系统文件管理器处理，不申请全盘权限。
- 0.1.0 的私有缓存不会自动迁移；新任务均使用公共目录。系统强制杀进程的未完成 Temp 会在过期后清理；尚未实现前台服务/进程恢复。


## 公开 API 核查与规格差异

核查日期：2026-10-05。沿用已核查的稳定版 HeifWriter 1.1.0，本轮未更换编码依赖或高级设置实现。依据官方文档、Google Maven metadata 及对应版本 sources.jar；没有使用反射、私有 API、codec hack 或自写 HEVC 编码器。

| 规格假设 | 核查结果与第一版实现 |
| --- | --- |
| HeifWriter 稳定版可用 EncoderPreference | [发布页](https://developer.android.com/jetpack/androidx/releases/heifwriter)及 [Maven metadata](https://dl.google.com/android/maven2/androidx/heifwriter/heifwriter/maven-metadata.xml)报告稳定版 **1.1.0**，最新预发布版 **1.2.0-beta01**（2026-09-23）。1.1.0 发布源码没有 EncoderPreference 或 setEncoderPreference。现有 0.1.0 实际固定使用 1.1.0，只实现 Auto；并未实现 Prefer Hardware / Prefer Software 或可选 CQ。0.2.0 完整保留该实现、CQ Auto 和 Codec 诊断，没有删除已有功能，也不把预发布/私有接口伪装为稳定 API。 |
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
| 分享会直接上传至 ChatGPT | 分享入口只保存 ZIP 并退出，由用户在 ChatGPT 文件选择器选取；桌面入口保留手动 Sharesheet。ChatGPT 是否接受 ZIP/HEIC、是否解压、是否二次压缩不属于 Android 应用能够保证的能力。没有自动上传或指定第三方私有组件。 |
| 任务后台常驻 | 分享流程有独立 ViewModel，旋转不重复处理、不丢失未确认命名或权限请求；后台完成后回到前台才提示并关闭。取消等待当前任务结束和 Temp 清理后关闭。系统同步解码与 HEIC stop 无法瞬间中断，HEIC stop 有 30 秒上限。未实现前台服务/进程死亡恢复；已确认的任务被系统杀死后不会自动重复生成 ZIP，而是提示重新分享，残留 Temp 下次过期清理，已发布的 Output 保留。 |

1.1.0 发布源码还表明默认创建的回调 HandlerThread 未自行退出，因此应用用公开 `setHandler` 提供自己管理的线程，并在 close 后 `quitSafely` / join，避免批量处理积累线程。`stop(timeoutMs)` 文档文字含 microsec 与参数名不一致，发布源码调用 Java `wait(timeoutMs)`，本版按毫秒设 30,000。

## 工程结构与实现顺序

1. 核查真实 API 和依赖版本，确定稳定版功能边界。
2. 建立 Gradle/Kotlin/Compose 工程及无网络权限清单。
3. 实现 `core/PackRules.kt`：默认参数、缩放、切片、保名重名、流式 ZIP。
4. `PackProcessor.kt` / `PackStorage.kt`：逐张/逐片 ImageDecoder → 编码 → 验证 → STORED ZIP → 公共 Output，资源释放、取消、fallback 和独立清理。
5. DataStore 保存图像设置、ZIP 命名和分辨率模式；ShareActivity / ShareViewModel 独立接收与小窗设置，MainActivity / PackViewModel 保留桌面设置、手动结果分享和 MediaCodec 能力诊断。
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

单元测试覆盖 HEIC 缺省 85 / JPEG 95、原始模式忽略所有降低比例、降低模式使用保存比例及先定尺寸再切片、切换模式保留质量和格式，以及原有缩放快捷值、16384 阈值、双轴切片、保名重名、ZIP 顺序/内容/size/CRC/STORED、10 MiB 流式输入与句柄关闭、输入变化导致 CRC 拒绝、取消、ZIP 名称/日期/非法名称。设备测试保留 HEIC 往返、PNG/长图/50 张/切片、公共文件及清理、分享路由，并检查小窗模式/名称/日期旋转保持、下次记忆、50% 的真实 8×12 输出与原始模式的 16×24 输出、已有质量不变、自动关闭和错误窗口。HEIC 设备测试要求真正生成 HEIC，PNG fallback 不算通过。

Lint 使用 `abortOnError=true` 与 `warningsAsErrors=true`，不使用 baseline。`app/lint.xml` 仅忽略 `GradleDependency` / `AndroidGradlePluginVersion` 两类“存在更新版本”的建议；这是对固定 API 36 工具链/依赖的显式选择，不关闭 API、安全、资源或代码正确性检查。较新的 Activity/Lifecycle/Core/Compose 版本存在，本版不要求追随最新依赖；HeifWriter 已特别核查并使用最新稳定版 1.1.0。Android 12+ 的云备份/设备迁移排除规则已显式配置。

## 验收状态

0.4.0 的最终 build / test / lint 验证结果见 [VALIDATION.md](VALIDATION.md) 及 `verification/`：Gradle build 成功，Debug/Release 各 17 个单元测试全部通过，lint 报告 No issues found；6 个设备测试已编译，但当前环境没有实机/可用模拟器，未执行。

记录见 `verification/` 和 [VALIDATION.md](VALIDATION.md)。可安装 Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`；源码交付包不包含 build 输出、SDK、本机代理或 local.properties。

当前环境无 Android 实机/可用模拟器；**设备测试只完成编译，没有执行**。硬件编码、系统 Sharesheet、50 张 HEIC、目标设备 19399 px 截图和长时间 native 内存/文件句柄观测仍需实机验收。设备测试 fixture 是合成图，不替代实际截图的 OCR 和颜色质量检查。

## Release Assets 上传

`upload-release-assets.yml` 是手动触发的发布工作流，输入已有的版本 tag。它先校验 downloads 中版本化的 SHA-256 文件，再上传已经在本地构建/签名的 APK 与源码 ZIP。用于执行环境的 GitHub 附件上传代理报 Content-Length 错误时，避免把源码目录下载链接冒充 Release Asset。工作流不重建/重签 APK，也不包含 signing key。
