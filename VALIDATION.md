# v0.6.1 验证记录

日期：2026-11-03。版本 **0.6.1 / versionCode 7**，基于默认分支中的 0.6.0 增量修改，保留原有架构和处理流程。

代码提交：`8c3c05b6e0d9cf299608e07eb2541db65088e64a`。发行 APK 的 `META-INF/version-control-info.textproto` 记录同一提交，见 `verification/apk-version-control.txt`；后续提交只整理文档、验证记录和发行附件。

## 本次修改

- 主界面“选择图片并测试”与新增“再次测试”并排显示。
- PackViewModel 保留最近选择的原图 URI 列表及顺序，并显示选择数量。再次测试调用原有 start/process 流程，读取当前参数，每次重新处理原图，生成独立 ZIP。
- 未选择图片、任务处理中、等待存储权限时禁用按钮；无效宽度仅限制宽度模式。取消选择不清空上一次选择，选择新图后替换。旋转继续使用同一 ViewModel；进程重新启动后不恢复选择。
- 仅保留 URI，不缓存 Bitmap，不对已缩放输出反复处理。取消、清理和 Android 9 存储授权沿用原流程。
- 分享小窗、A/B/指定宽度、质量、格式、命名、切片、HEIC 回退、ZIP 和公共 Output 未改。

## 构建与检查

环境：Temurin JDK **17.0.21+9**、Gradle **8.13**、AGP **8.13.0**、Kotlin **2.2.20**、Android Platform **36 r02**、Build Tools **35.0.0**。依赖版本保持不变。

```text
./gradlew build testDebugUnitTest lintDebug lintRelease assembleDebugAndroidTest --console=plain
BUILD SUCCESSFUL in 5m
145 actionable tasks: 55 executed, 90 up-to-date
```

最终日志见 `verification/gradle-build.log`。`verification/lint-release-build.log` 保存同一次包含 lintRelease 的完整构建记录。Debug、Release、Android 设备测试 APK 均编译/打包成功。

Debug / Release 各运行同一组 **24 项单元测试**，各为 0 失败、0 错误、0 跳过：PackRulesTest 10 项、ZipNamingTest 5 项、ResolutionModeTest 9 项。覆盖既有 ZIP/命名/比例/宽度/切片规则；不是 48 项不同用例。六份最终 JUnit XML 已更新。

Android Lint **Debug 和 Release 均为 No issues found**。继续启用 abortOnError / warningsAsErrors，不新增 baseline 或豁免。

## APK 与覆盖安装

发行文件 `GPT-Screenshot-Pack-0.6.1.apk` 来自不可调试的 **Release** 变体。

已核对：

- 包名 `com.gptscreenshotpack`，versionName=0.6.1，versionCode=7，minSdk=28，targetSdk=36。
- APK v2 签名有效，与 0.6.0 及此前版本使用同一证书，支持覆盖安装。
- 证书 SHA-256：`e6026d6cf3d969afe91ca9a266d879d379ad8299dda863190a2c5c3fa082d354`。
- 无 `application-debuggable`、INTERNET、MANAGE_EXTERNAL_STORAGE 标记/权限，WRITE_EXTERNAL_STORAGE 仍仅 maxSdkVersion=28。
- APK 16 KiB ZIP 对齐检查通过；证据见 `verification/apk-*.txt`。

沿用先前从用户 Google Drive 恢复的签名备份，通过本机环境变量签名；密钥和密码不进入仓库、源码包或发行附件。

## 设备测试边界

**9 项 Android 设备测试已编译，但未执行。** 当前没有连接 Android 手机，也没有可用的硬件加速模拟器；本轮未运行模拟器或实机验收。

新增 MainRetestTest 验证场景：初始按钮禁用，选择两张原图后按 A=50% 输出 8×12，旋转保留选择，点击“再次测试”按 B=75% 输出 12×18，再切换指定宽度 20 px 输出 20×30；检查原图字节不变、每次生成独立 ZIP。该场景已编译，仍待设备执行。

原有 8 项设备测试保留，包含分享窗布局、格式/模式/宽度/旋转/记忆、公共输出、切片及 HEIC 往返。连接手机后运行 `./gradlew connectedDebugAndroidTest`。真实选图提供方的 URI 授权、按钮布局/操作、页面旋转和厂商编码器仍需实机确认。

## 发行内容

默认分支更新源码、README、规格、变更记录和验证证据。v0.6.1 完整发行附件包含 APK、干净源码 ZIP、SHA-256 校验文件；源码 ZIP 排除 .git、downloads、SDK、build、本机配置及签名文件。最终输出目录仍为 `Download/GPT Screenshot Pack/Output/`。
