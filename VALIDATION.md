# v0.3.0 验证记录

日期：2026-10-05（Asia/Shanghai）。版本 **0.3.0 / versionCode 3**，在同一 GitHub 项目的 0.2.0 上继续修改，没有重建项目。

环境：Temurin JDK 17.0.20.1、Gradle 8.13、Android Platform 36 r02、Build Tools 35.0.0。

```text
./gradlew build testDebugUnitTest lintDebug assembleDebugAndroidTest --console=plain
BUILD SUCCESSFUL in 9s
138 actionable tasks: 13 executed, 125 up-to-date
```

首次修改后完整构建也已通过（37 秒）；以上最终验证包含新增的命名与分享设备测试。未改变依赖和 lint 策略。

## 单元测试与 Lint

Debug / Release 各运行同一组 **14 个单元测试**：原有 PackRulesTest 9 个、ZipNamingTest 5 个。均 0 失败、0 错误、0 跳过，不是 28 个不同测试。

- 原有默认 100%、缩放/质量快捷值、16384 阈值与均匀切片、图片命名/重名、图片专用 STORED ZIP、size/压缩大小/CRC32、10 MiB 双遍有界流、流关闭、输入 CRC 变化及取消清理继续通过。
- 新增 ZIP 命名默认值/日期默认开启、手机本地时区的 yyyyMMdd_HHmmss、关闭日期、中文/空格与扩展名归一化、空白/路径/非法字符拒绝、UTF-8 长度和日期/重名预留空间。

Android Lint：**No issues found**。保持 abortOnError / warningsAsErrors，不使用 baseline，仅保留原有 GradleDependency / AndroidGradlePluginVersion 更新建议豁免。报告及 4 份单元测试 XML 位于 verification。

## 可安装 APK 与签名

- GPT-Screenshot-Pack-0.3.0-debug.apk，已验证 v2 签名。
- 无 release signing 配置，Release variant 为 unsigned；交付使用原 debug keystore 的可安装 Debug APK。
- 与 0.1.0 / 0.2.0 证书 SHA-256 一致：e6026d6cf3d969afe91ca9a266d879d379ad8299dda863190a2c5c3fa082d354，可覆盖安装；没有上传 signing key。
- aapt 检查 applicationId=com.gptscreenshotpack、versionCode=3、versionName=0.3.0、minSdk=28、targetSdk=36。
- 无 INTERNET / MANAGE_EXTERNAL_STORAGE；WRITE_EXTERNAL_STORAGE 仅 maxSdkVersion=28。
- MAIN/LAUNCHER 仅由 MainActivity 接收；SEND / SEND_MULTIPLE 的 image/* 仅由独立 ShareActivity 接收。ShareActivity 使用透明主题、空 taskAffinity、excludeFromRecents，退出只 finish 自己。

## Android 设备测试边界

当前环境没有 Android 实机或可用模拟器，**6 个设备测试已编译但未执行**，不能把编译结果视为实机通过。

- 原有 4 个设备测试继续检查 HEIC 真实往返、PNG/长图/50 张/切片/失败、公共 Output/pending/同名不覆盖、无网络权限和入口路由；增加活跃 Temp 不被主界面清理的断言。
- 新增 2 个 ShareFlowTest：确认前等待命名；名称和日期开关旋转后保持；双图自定义 ZIP 保持图片命名与 STORED；成功自动关闭、下次记住偏好、从未启动 MainActivity；ClipData fallback 与全部失败保留错误/重试/关闭。
- 设备测试恢复原图像/命名偏好，清理自己的测试输出，不调用全量 Output 清理。

还需实机验收：来源 App 的真实分享/返回行为、命名窗口软键盘和外观、Android 9 授权、Android 10+ 文件选择器可见性、实际 HEIC codec、超长截图/native 内存及厂商系统行为。连接设备后运行 ./gradlew connectedDebugAndroidTest。

## 存储与生命周期

- 最终 ZIP：Download/GPT Screenshot Pack/Output/，分享用确认后的名称；桌面测试用默认时间名称。同名追加后缀，不覆盖。
- 中间图片/未完成 ZIP：Download/GPT Screenshot Pack/Temp/<UUID>/。清理 Temp 跳过进程内的活跃任务。
- Output 永不自动删除；确认后的手动清理支持自定义名称。Android 10+ 限 owner/目录/ZIP MIME/扩展名；Android 9 无 owner 列，只清理专用 Output 内的 ZIP，请勿放入无关 ZIP。
- 分享成功后提示实际保存名称并关闭，不启动 MainActivity 或系统 Sharesheet。桌面仍保留手动分享按钮及全部图像/诊断/清理功能。
- 旋转不重启任务；取消等待清理；进程死亡后已提交的任务不自动重跑，重新分享即可，已发布 Output 保留。没有新增前台服务。

## 发布

提交源码、规格、README 与验证记录，创建 v0.3.0 tag / GitHub Release。附件为已验证的同签名 debug APK、源码 ZIP 和 SHA-256 文件。若云端直传代理仍拒绝 Content-Length，使用现有手动发布工作流校验并上传同一批产物，不重新签名或上传密钥。
