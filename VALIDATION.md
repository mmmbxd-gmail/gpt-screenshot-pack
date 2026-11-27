# 0.7.0 验证记录

2026-11-27。版本 0.7.0，versionCode 8。发行 APK 内的 Git 源码版本为 `e9b1cc3ad63836a45e43b98911e26ffaf85b424b`。

## 构建与签名

- 使用 JDK 17、Gradle 8.13、Android SDK 36 r02 和 Build Tools 35.0.0 构建 Debug、Release 和 Android 测试 APK。
- Debug、Release 各运行 25 项单元测试，均通过。两个变体的 Android Lint 均为零问题。
- Release APK 通过 v2 签名和 16 KiB ZIP 对齐校验。签名证书 SHA-256 与 0.6.1 相同，为 `e6026d6cf3d969afe91ca9a266d879d379ad8299dda863190a2c5c3fa082d354`。
- 发行 APK 为不可调试的完整 APK，支持 arm64-v8a、armeabi-v7a、x86 和 x86_64。最低 API 28，目标 API 36，存储写权限限 API 28。签名私钥只用于本地构建。

## PDF 结构与体积

使用生产 PDF 写入器生成横图、超长图、竖图三页样本。Poppler 和 PyMuPDF 可读取文件，并分别解析出 240 × 160、49.3714 × 14,400、160 × 240 点的页面。超长图的三个 JPEG 分片位于同一页。以同样页面和图像像素为基准，JPEG PDF 为 48,554 字节，未压缩 RGB 版为 8,296,433 字节，样本体积减少约 99.4%。

## Android 验证状态

Android 测试 APK 已构建。新增用例覆盖 PDF 页序、等比页面、透明图背景、质量设置、分享后关闭，以及超长 JPEG 的区域解码与 EXIF 方向。云端 Android 15 软件模拟器出现系统服务重启和共享存储无法挂载。按当前验证状态，Android 端到端和 Google Drive 打开测试尚未完成。

发行附件包含签名 APK、源码 ZIP、SHA-256 校验文件。
