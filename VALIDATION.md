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

已在 GitHub Actions 的 Android 15 模拟器上安装 0.6.1，并成功覆盖安装 0.7.0 的签名 Release APK。[安装记录](https://github.com/mmmbxd-gmail/gpt-screenshot-pack/actions/runs/46071323862)

Android 15 模拟器的 13 项设备测试全部通过，覆盖 PDF 页序、等比页面、透明图背景、质量设置、分享后关闭、竖屏布局、ZIP 回归和 HEIC 往返。超长 JPEG 用例验证了 EXIF 方向以及 60,000 × 990 像素目标的切片。[通过的测试记录](https://github.com/mmmbxd-gmail/gpt-screenshot-pack/actions/runs/46072510483)

应用导出的三页样本大小为 8,531 字节。Android `PdfRenderer` 逐页渲染并验证颜色和顺序。模拟器上的 Google Drive PDF 阅读器打开该文件，截图像素确认第一页已渲染。[PDF 实际打开记录](https://github.com/mmmbxd-gmail/gpt-screenshot-pack/actions/runs/46072510483)

设备测试的 JUnit 返回类型修正位于 `main` 的 `d19424a`，仅涉及测试文件。发行 APK 使用上文记录的应用源码版本。厂商真机上的硬件 HEIC 编码超出此次模拟器实测范围。

发行附件包含签名 APK、源码 ZIP、SHA-256 校验文件。
