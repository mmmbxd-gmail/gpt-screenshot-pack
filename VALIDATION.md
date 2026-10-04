# 第一版验证记录

日期：2026-10-04，版本 0.1.0。最终源码本地构建环境：Temurin JDK 17.0.20.1、Gradle 8.13、Android Platform 36 r02、Build Tools 35.0.0。

```text
./gradlew build testDebugUnitTest lintDebug assembleDebugAndroidTest --console=plain
BUILD SUCCESSFUL in 29s
132 actionable tasks: 67 executed, 65 up-to-date
```

Debug 与 Release 各运行 `PackRulesTest` 的 5 个测试；两种 variant 都无失败、错误或跳过，不把重复执行同一组测试说成 10 个不同测试。没有使用 Android mock 的默认返回值来掩盖失败。

Lint：No issues found。保持 warningsAsErrors / abortOnError，不建 baseline；仅豁免两个已说明的依赖/工具链更新建议。此前发现的 DataExtractionRules 问题已修复，并重新完整构建。

APK 签名通过 `apksigner verify --verbose`，APK 级权限通过 `aapt dump permissions` 验证。唯一 uses-permission 为 AndroidX 动态接收器的应用内部 signature 权限，无 INTERNET、存储读写或媒体广泛访问权限。

## 规格第 29 节验收状态

| 项 | 状态 / 证据 |
| --- | --- |
| 1 JPG → HEIC → ZIP | 设备测试已编译，待 Android 16 执行；HEIC 失败不算本项通过 |
| 2 分享菜单多图 | 分享过滤器和顺序处理已实现，待实机完整流程 |
| 3 PNG 输入 | 设备测试含 PNG 50 张，待执行 |
| 4 HEIC 输入 | 设备测试含 HEIC 重新输入，待执行 |
| 5 20～50 张不崩溃 | 已实现逐张释放；50 张 PNG 测试待执行，50 张真实 HEIC 待实机压力验证 |
| 6 19399 px 长图 | 1440×19399 → 720×9700 的尺寸/不切片规则单测通过；设备测试含窄幅合成图，真实截图待验证 |
| 7 >16384 自动切片 | 核心面积、顺序、均衡、阈值单测通过；设备测试含彩色分段逐像素顺序检查，待执行 |
| 8 输出 HEIC 再解码 | 每次 HEIC 写入后自动做尺寸与缩略解码校验，设备往返测试待执行 |
| 9 ZIP 解压 | JVM ZipFile 解压并逐字节比对通过 |
| 10 ZIP 只含图片 | ZIP 项名称和顺序单测通过；打包拒绝空列表、重复名称和非图片扩展名 |
| 11 原始名称主体 | Unicode 和时间主体单测通过 |
| 12 resized_ | 单测通过 |
| 13 切片 _1/_2/_3 | 命名和名称碰撞单测通过，设备输出测试待执行 |
| 14 输入顺序 | ZIP 单测和切片单测通过；分享/选择器提供的输入顺序在处理层保留，系统实际提供顺序需实机确认 |
| 15 Sharesheet 分享 ZIP | FileProvider、ClipData、临时读取授权已实现，待实机目标应用验证 |
| 16 原文件不变 | 实现仅查询/解码输入 URI，输出和删除均限定应用缓存；设备字节比对测试待执行 |
| 17 无网络权限 | 最终主 APK aapt 实查通过 |
| 18 无明显泄漏 | finally / use / recycle / 自管回调线程已实现；文件句柄/native/GPU/线程压力观测待实机 |
| 19 Gradle build | 最终完整构建通过 |
| 20 Android Lint | 最终 lintDebug 通过，见上述显式建议豁免 |

## 实机运行

连接启用 USB 调试的 Android 16 设备后，先运行 `./gradlew connectedDebugAndroidTest`，再安装 Debug 主 APK，从相册实际分享 20～50 张截图。分别测试 HEIC/PNG/JPEG、19399 px 长截图、损坏输入、透明图片、取消、旋转、后台完成后返回和缓存清理。查看编码器信息；实际 codec/mode 为 Unknown 是稳定 API 的已记录限制。

分享接收方是否接受 ZIP 和 HEIC 需在实际 ChatGPT 应用中确认，不能由编译/单元测试推断。真实文字识别质量不属于当前合成图测试能够验证的内容。
