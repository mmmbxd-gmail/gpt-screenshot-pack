# v0.6.0 验证记录

日期：2026-11-03（UTC）。版本 **0.6.0 / versionCode 6**，直接在现有 0.5.0 项目上迭代。保留 Activity / ViewModel / DataStore / PackProcessor / PackStorage 架构及既有编码、切片和公共目录流程。

代码提交：`c2431e94750cb9a61f186f8cb0e55a2b90042557`。发行 APK 内的 `META-INF/version-control-info.textproto` 记录了同一提交，见 `verification/apk-version-control.txt`。后续提交只整理文档、验证记录和发行附件。

## 构建

环境：Temurin JDK **17.0.21+9**、Gradle **8.13**、AGP **8.13.0**、Kotlin **2.2.20**、Android Platform **36 r02**、Build Tools **35.0.0**。未升级运行时依赖。

使用本机环境变量提供原有签名，密钥和密码不进入源码。最终命令及结果：

```text
./gradlew build testDebugUnitTest lintDebug assembleDebugAndroidTest --console=plain
BUILD SUCCESSFUL in 2m 6s
139 actionable tasks: 25 executed, 114 up-to-date

./gradlew lintRelease --console=plain
BUILD SUCCESSFUL in 52s
28 actionable tasks: 6 executed, 22 up-to-date
```

完整日志见 `verification/gradle-build.log` 和 `verification/lint-release-build.log`。Debug / Release 的 Kotlin、Java、APK 及设备测试 APK 均已编译/打包成功。

## 单元测试和 Lint

Debug / Release 各运行同一组 **24 个单元测试**，均为 **0 失败、0 错误、0 跳过**；不是 48 个不同用例。

| 测试组 | 每个变体的数量 | 覆盖内容 |
| --- | ---: | --- |
| PackRulesTest | 10 | 原有质量/比例快捷值、图片名、切片、DEFLATED / BEST_SPEED ZIP、顺序/内容/CRC、两遍 64 KiB 有界流、关闭/取消/变化拒绝 |
| ZipNamingTest | 5 | ZIP 主体、日期、扩展名、非法字符、UTF-8 长度和重名规则 |
| ResolutionModeTest | 9 | 原始模式忽略比例、A 的旧逻辑、B 独立生效、宽度模式横竖图/混合尺寸等比计算、四舍五入/放大/最小高度、双轴切片、非正宽度/高度溢出、极端比例计算不发生 Int 乘法溢出、新默认值及 REDUCED 旧存储值 |

六份最终 JUnit XML 位于 `verification/`。Android Lint **Debug 和 Release 均为 No issues found**，报告已保存；继续保持 `abortOnError=true` / `warningsAsErrors=true`，不使用 baseline，保留原有两类版本更新建议豁免。

## 功能与兼容性

- 分享小窗用下拉菜单替代纵向单选列表，四种选项为原始、比例 A、比例 B、指定宽度。图片数量并入标题，格式选项同行；只显示当前模式说明和按需出现的宽度框。保留必要的滚动兜底。
- A 沿用 `scale` 和 `REDUCED`，保留既有值、25%～100% 范围、快捷值及缺省 100%；B 独立存于 `scale_b`，缺省 75%。
- 指定宽度 `target_width` 缺省 1080 px，主界面和分享小窗可改。输出宽度精确等于设定值，高度逐张按原始比例四舍五入且至少 1，可缩小或放大；之后进入同一切片/解码/编码/ZIP 流程。
- 分享确认时校验正整数并原子保存模式/格式/名称/日期/当前宽度；取消和校验失败不覆盖偏好，不改 A/B 或质量。宽度和模式保留在原有 SavedStateHandle，主界面同步已确认宽度。
- 保留 HEIC 85 / JPEG 95 默认质量、HEIC/JPEG/PNG、HEIC 失败回退 PNG、名称日期、BEST_SPEED ZIP、输入顺序、公共 Output、不覆盖旧 ZIP、独立清理/取消、自动返回来源 App 和桌面诊断。
- 主界面无效宽度只阻止宽度模式的测试，不影响原始/A/B 模式。

## APK 与签名

发行文件：`GPT-Screenshot-Pack-0.6.0.apk`，来自 **Release** 变体，已核对无 `application-debuggable` 标记。沿用原有开发签名证书，v2 签名验证通过；与仓库中实际 0.5.0 APK 的证书逐项比较一致，支持相同包名覆盖安装。

证书 SHA-256：`e6026d6cf3d969afe91ca9a266d879d379ad8299dda863190a2c5c3fa082d354`。

密钥从用户 Google Drive 的原始签名备份恢复，仅用于本机构建；未加入仓库、源码包或发行附件。可选签名通过 `PACK_KEYSTORE` / `PACK_STORE_PASSWORD` / `PACK_KEY_ALIAS` / `PACK_KEY_PASSWORD` 配置，未设置时保留 unsigned Release。

已验证：包名 `com.gptscreenshotpack`、versionCode=6、versionName=0.6.0、minSdk=28、targetSdk=36；无 INTERNET / MANAGE_EXTERNAL_STORAGE，WRITE_EXTERNAL_STORAGE 仅 maxSdkVersion=28。启动和分享路由保持，APK 的 16 KiB ZIP 对齐检查通过。证据见 `verification/apk-*.txt`。

最终 ZIP 目录继续为 `Download/GPT Screenshot Pack/Output/`；中间文件位于 `Temp/<UUID>/`。输出 ZIP 仅包含图片。

## 设备测试边界

**8 项设备测试已编译，但未执行。** 当前没有实体 Android 手机；环境没有 `/dev/kvm`，API 35 软件模拟器长时间未完成系统初始化，安装尝试返回 `Can't find service: package`，窗口查询返回 `Can't find service: window`，未能运行应用测试。

新增设备测试已覆盖以下待运行场景：

- 分享下拉菜单四种选项、原始/宽度两种布局的内容可见性和纵向滚动范围为零断言，及真实截图采集。
- B 比例实际输出 12×18、宽度模式放大输出 20×30、无效宽度不提交、旋转保留、下次记忆、取消保留旧值、质量/A/B 不变、已打开主界面同步。

原有 HEIC 往返、PNG/JPEG 实际输出、50 张/长图/切片、公共输出/清理/重名、分享返回与错误显示用例均保留。连接手机后运行 `./gradlew connectedDebugAndroidTest`。

实际手机上的一屏布局、键盘/大字体行为、Android 9 存储授权、厂商 HEIC、相册分享、真实截图/OCR 和第三方上传兼容性仍需目标手机验收。此次没有把编译成功记作设备测试通过，也没有生成未经验证的界面截图。

## 发行内容

仓库内更新源码、README、规格、变更记录及验证文件；v0.6.0 发行附件包含不可调试且沿用原签名的 APK、干净源码 ZIP 和 SHA-256 校验文件。源码 ZIP 排除 `.git`、downloads、SDK、构建输出、本机配置和签名文件。
