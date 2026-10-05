# v0.2.0 验证记录

日期：2026-10-05（Asia/Shanghai），版本 0.2.0 / versionCode 2。在现有 0.1.0 项目和 GitHub main 历史上迭代，没有重建项目。

环境：Temurin JDK 17.0.20.1、Gradle 8.13、Android Platform 36 r02、Build Tools 35.0.0。

```text
./gradlew build testDebugUnitTest lintDebug assembleDebugAndroidTest --console=plain
BUILD SUCCESSFUL in 24s
138 actionable tasks: 28 executed, 110 up-to-date
```

Debug / Release 各运行同一组 9 个单元测试，均 0 失败、0 错误、0 跳过。不是 18 个不同测试。检查覆盖默认 100%、质量/缩放快捷值、16384 阈值、均匀切片、IMG/中文/时间主体和重名命名、STORED / size / compressedSize / CRC32、10 MiB 生成流的双遍有界读写和关闭、CRC 变化拒绝、校验与写入阶段的取消清理、ZIP 只包含按顺序命名的图片以及空 ZIP 拒绝。

Android Lint：**No issues found**。保持 abortOnError / warningsAsErrors，不使用 baseline；仅保留旧版明确说明的两类依赖/工具链更新建议豁免。

## 可安装 APK 与签名

- 主 APK：GPT-Screenshot-Pack-0.2.0-debug.apk，已验证 v2 签名。
- 项目没有 release signing 配置，Release 构建为 unsigned；交付沿用原 debug keystore 的 Debug APK。
- 与 0.1.0 的签名证书 SHA-256 一致：`e6026d6cf3d969afe91ca9a266d879d379ad8299dda863190a2c5c3fa082d354`。可以覆盖安装，签名密钥没有上传到仓库。
- aapt 检查：applicationId=com.gptscreenshotpack、versionCode=2、versionName=0.2.0、minSdk=28、targetSdk=36。
- 无 INTERNET 或 MANAGE_EXTERNAL_STORAGE。只有 WRITE_EXTERNAL_STORAGE 且 maxSdkVersion=28；Android 10+ 使用 MediaStore，不申请该权限。

## 真实 Android 测试边界

当前环境没有 Android 设备或可用模拟器，**4 个设备测试已编译但未执行**。其中包括 HEIC 原始尺寸往返、PNG 50 张与默认 100% 的长截图切片、像素顺序/PNG 色深、部分/全部失败、公有 Output 路径/IS_PENDING 发布、Temp 清理保留 Output、同名 ZIP 不覆盖以及分享过滤器。

公共 MediaStore 的实际设备行为、Android 9 授权/移动、Android 16 文件选择器可见性、Sharesheet、真实长截图、50 张 HEIC 及 native/GPU 内存压力仍需实机验收。本地单元测试和 APK 编译不代表这些项目已经实机通过。

## 公共文件与取消

- 最终 ZIP：`Download/GPT Screenshot Pack/Output/GPT_Screenshots_yyyyMMdd_HHmmss.zip`，同秒重名保留旧文件并追加后缀。
- 中间文件和未完成 ZIP：`Download/GPT Screenshot Pack/Temp/<任务 UUID>/`。
- Temp 的完成/失败/取消和 24 小时过期清理不会自动删除 Output；Output 只能通过用户确认后的“清理生成文件”操作清理本应用可访问的生成 ZIP。
- 清理不访问原始输入 URI 的父目录。取消发生在公开发布完成之后时，完成的 ZIP 仍保留在 Output。

## 发布过程

源码和文档一起提交到当前 GitHub 仓库并创建 v0.2.0 tag。已验证/签名的 APK、源码 ZIP 和 SHA-256 文件存放在 downloads。优先直传 Release Assets；若执行环境的上传代理报 Content-Length 错误，使用仓库 workflow_dispatch 发布工作流从 GitHub runner 上传同一批校验过的文件，不重新签名、不上传 keystore。

连接 Android 16 实机后运行 `./gradlew connectedDebugAndroidTest`，从相册真实分享截图，随后在 ChatGPT 对话的文件选择器进入“下载 → GPT Screenshot Pack → Output”选择 ZIP。接收方能否接受 ZIP/HEIC 和文字识别质量仍需实际确认。
