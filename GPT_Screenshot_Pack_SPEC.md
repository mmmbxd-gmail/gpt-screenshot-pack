# GPT Screenshot Pack

> 当前规格版本：0.3.0（2026-10-05）。在既有 Android 项目上迭代：独立分享命名流程、保存 ZIP 后退出；保留默认 100%、公共 Downloads、STORED ZIP 和独立清理。公开 API 差异继续以 README 为准。

## 1. 项目目标

开发一个个人使用的 Android 图片预处理工具，主要用于：

**手机截图 → 保留分辨率（默认 100%，可选缩放）→ HEIC 编码 → STORED ZIP → 公共 Downloads / 分享至 ChatGPT**

主要目的：

- 减少大量截图上传时的文件体积；
- 尽量保持聊天截图中文字的可识别性；
- 避免普通图片上传流程可能产生的额外图片压缩；
- 保留原始截图文件名中的时间等信息；
- 日常通过 Android 分享菜单快速完成处理。

应用所有处理均在本地完成。

---

## 2. 目标环境与技术栈

主要测试环境：

- Android 16
- Snapdragon 8 Elite 级设备

但不得针对具体 SoC 硬编码能力。

技术要求：

- Kotlin
- Jetpack Compose
- DataStore 保存设置
- AndroidX `HeifWriter`
- Android `MediaCodec`
- Android `ImageDecoder`
- `FileProvider`
- ZIP 标准库

不要引入：

- FFmpeg
- x265
- libheif
- 自行实现的 HEVC 编码器

应用不申请网络权限。

---

## 3. 两种使用入口

### 3.1 Android 分享菜单

必须支持：

- `ACTION_SEND`
- `ACTION_SEND_MULTIPLE`
- `image/*`

典型流程：

```text
相册多选截图
→ 分享
→ GPT Screenshot Pack
→ ZIP 命名窗口
→ 使用保存的图像设置处理
→ 保存 ZIP 到公共 Output
→ 自动关闭分享 Activity，返回来源 App
```

通过分享菜单调用时：

- 默认直接使用上次保存的设置；
- 不进入正常主界面、不要求每次确认图像参数；
- 先确认 ZIP 名称主体和日期开关，再显示简洁处理进度；
- 成功保存 ZIP 后提示实际名称并自动关闭，不打开系统 Sharesheet；全部失败则显示错误，不生成空 ZIP。
- `ACTION_SEND` / `ACTION_SEND_MULTIPLE` 由独立 ShareActivity 接收，桌面 MAIN/LAUNCHER 继续由 MainActivity 接收。

### 3.2 主界面

保留正常桌面图标。

主界面主要用于：

- 修改处理参数；
- 手动选择图片测试；
- 查看处理结果；
- 查看实际编码器能力；
- 管理缓存。

主界面不承担复杂图片编辑功能。

---

## 4. 输入格式

第一版至少支持：

- JPG / JPEG
- PNG
- HEIC / HEIF

其他 Android `ImageDecoder` 能稳定处理的图片格式可以兼容，但不是第一版强制目标。

任何情况下都不得修改原始文件。

---

## 5. 分辨率缩放

默认：

```text
100%（保留原始分辨率）
```

如选择 50%，宽度和高度同时按照相同比例缩放：

```text
1440 × 3200
→
720 × 1600
```

保持宽高比，不裁剪。

主界面允许调整：

```text
25% ～ 100%
```

建议快捷值：

```text
33%
50%
60%
67%
75%
100%
```

默认：

```text
100%（保留原始分辨率）
```

---

## 6. 图片解码与内存控制

优先直接按照目标尺寸解码图片。

推荐：

```text
原图
→ ImageDecoder
→ 设置目标尺寸
→ 目标尺寸 Bitmap
```

尽量避免：

```text
完整尺寸 Bitmap
→ 再创建第二张缩小 Bitmap
```

处理大量截图时必须：

- 逐张解码；
- 逐张编码；
- 编码完成后及时释放上一张图片资源；
- 不允许一次性将几十张完整截图载入内存。

---

## 7. 输出格式

支持：

```text
HEIC
PNG
JPEG
```

默认：

```text
HEIC
```

### HEIC

默认质量：

```text
95
```

范围：

```text
1 ～ 100
```

推荐快捷值：

```text
50
75
85
90
95
100
```

HEIC 是默认日常格式。

### PNG

PNG 作为：

- 无损测试基准；
- HEIC 兼容性 fallback；
- OCR 对照格式。

要求：

- 8 bit/channel；
- RGB 或 RGBA True Color；
- 不主动转换为 indexed/palette PNG；
- 不主动降低色深；
- 不进行有损量化。

PNG 模式不显示“质量”参数。

Deflate level、filter 等无损编码细节交给成熟编码实现处理。

### JPEG

作为最高兼容性的备用模式。

默认质量：

```text
95
```

不作为正常工作流首选。

---

## 8. HEIC 编码方案

HEIC 使用：

```text
AndroidX HeifWriter
→ Android MediaCodec
→ 系统 HEIC / HEVC 编码器
```

不直接控制：

- QP
- CTU
- SAO
- Deblocking
- Reference Frames
- x265 preset
- 其他底层 HEVC 专用参数

由 Android 和设备编码器负责。

---

## 9. 编码器模式

主界面提供：

```text
编码器
```

三个模式。

### 自动

默认。

```text
Auto
```

由 AndroidX / MediaCodec 根据设备能力选择合适编码器。

正常使用推荐此模式。

### 优先硬件

```text
Prefer Hardware
```

优先选择硬件加速编码器。

如果硬件编码器无法满足当前图片要求，应允许 fallback。

### 优先软件

```text
Prefer Software
```

用于：

- 对比软硬件编码质量；
- 排查硬件 codec 问题；
- 调试兼容性。

不要求第一版提供强制：

```text
Hardware Only
Software Only
```

如果 AndroidX 公共 API 可以低成本稳定支持，可以放入高级设置。

不得为了实现这些选项使用私有 API。

---

## 10. CQ 设置

提供：

```text
CQ / Constant Quality
```

设置。

### 自动

默认。

```text
Auto
```

如果所选 codec 支持 CQ，则允许使用 CQ。

不支持时自动使用 AndroidX / MediaCodec 提供的兼容模式。

### 优先 CQ

```text
Prefer CQ
```

优先选择：

- 支持 CQ 的 codec；
- CQ bitrate mode。

如果无法满足，应允许 fallback。

### 禁用 CQ

```text
Disable CQ
```

主要用于对照实验。

如果当前 AndroidX 公共 API 无法可靠表达“禁用 CQ”，则：

- 不使用私有 API；
- 不进行 codec hack；
- 可以隐藏此选项；
- 在编码器诊断页面显示最终实际采用的模式。

---

## 11. 默认编码组合

第一版默认：

```text
输出格式：HEIC
缩放比例：100%
HEIC Quality：95
编码器：Auto
CQ：Auto
Grid：Auto
```

正常用户不需要理解 MediaCodec 即可使用。

---

## 12. HEIF Grid

HEIF Grid 默认交由 `HeifWriter` 自动处理。

不在普通设置界面提供：

```text
Grid On / Off
```

如果底层 HEVC encoder 无法直接处理某个尺寸，应允许 AndroidX 自动使用 HEIF Grid。

必须区分：

```text
HEIF Grid
```

只是 HEIF 文件内部的编码 tile。

它不等于把一张图片切成多个独立图片文件。

---

## 13. 超长图片

将：

```text
16384 px
```

定义为应用的**兼容性安全阈值**。

它不是 HEIF 标准理论极限。

判断使用**缩放后的最终尺寸**。

规则：

```text
最终宽度和高度均 ≤ 16384
→ 保持单张图片
```

如果：

```text
任意一边 > 16384
```

则：

```text
→ 自动切片
```

当前测试设备的系统长截图最大高度约：

```text
19399 px
```

默认 100% 保持约 19399 px，超过 16384 px，因此均匀分为两片。

如果手动选择 50%，缩放后约为：

```text
9700 px
```

因此 50% 的上述长截图不会触发切片；默认 100% 则按最终尺寸执行切片，绝不偷偷缩小分辨率。

---

## 14. 自动切片

只有处理后的图片超过 16384 px 时强制执行。

原则：

- 沿超长方向切片；
- 保持原始方向；
- 尽量均匀分配；
- 避免最后产生特别小的一片；
- 每片明显低于 16384 px；
- 不改变各片之间的顺序。

例如处理后的图片：

```text
720 × 28000
```

可以均匀拆为若干片，而不是强行切成：

```text
16384 + 11616
```

具体切片尺寸由实现根据图片尺寸合理计算。

---

## 15. 图片文件命名

默认必须保留原始文件名主体。

在原文件名前添加：

```text
resized_
```

用于明确表示图片已经缩小过分辨率。

例如：

```text
原始：
Screenshot_20261005_013022.jpg
```

转换为 HEIC：

```text
resized_Screenshot_20261005_013022.heic
```

转换为 PNG：

```text
resized_Screenshot_20261005_013022.png
```

转换为 JPEG：

```text
resized_Screenshot_20261005_013022.jpg
```

要求：

- 保留原始文件名主体；
- 保留原文件名中的时间信息；
- 仅替换扩展名；
- 不重新生成流水号；
- 不主动修改中文、数字、时间等合法字符。

第一版默认前缀：

```text
resized_
```

可以在以后增加自定义前缀功能。

---

## 16. 切片命名

如果：

```text
Screenshot_20261005_013022.jpg
```

被处理并拆成三片，则输出：

```text
resized_Screenshot_20261005_013022_1.heic
resized_Screenshot_20261005_013022_2.heic
resized_Screenshot_20261005_013022_3.heic
```

规则：

```text
_1
_2
_3
...
```

放在扩展名前。

不得改成：

```text
001.heic
002.heic
003.heic
```

---

## 17. 文件重名

如果输入中出现两个完全相同的文件名，不允许覆盖。

可以增加：

```text
_copy2
_copy3
```

例如：

```text
resized_Screenshot_20261005_013022.heic
resized_Screenshot_20261005_013022_copy2.heic
```

如果第二个文件又发生切片：

```text
resized_Screenshot_20261005_013022_copy2_1.heic
resized_Screenshot_20261005_013022_copy2_2.heic
```

---

## 18. ZIP 文件

所有输出图片打包成一个 ZIP。

桌面手动测试的 ZIP 默认按时间命名，分享入口默认主体同为 GPT_Screenshots，但支持用户自定义：

```text
GPT_Screenshots_yyyyMMdd_HHmmss.zip
```

例如：

```text
GPT_Screenshots_20261005_013022.zip
```

分享命名窗口：

- 输入主体，自动补 `.zip`；已粘贴的 `.zip` 后缀不重复添加。
- “自动附加日期时间”默认开启，使用手机本地时区、确认时的 `yyyyMMdd_HHmmss`。
- 例如 `聊天记录_20261005_132530.zip`；关闭日期开关时为 `聊天记录.zip`。
- 使用 DataStore 记住上一次确认的主体和开关；未确认/取消的修改不覆盖记忆。
- 空白、路径分隔符、控制字符或常见非法文件名字符需修改；主体上限 200 UTF-8 字节，为时间及重名后缀留空间。
- 同名追加 `_copy2` 等，不覆盖既有 ZIP。图片命名与切片编号规则不变。

ZIP 内：

**只允许包含处理后的图片。**

不要生成或加入：

- `manifest.txt`
- README
- JSON metadata
- CSV
- OCR 内容
- 编码器日志
- 处理说明
- 任何其他文本文件

目的是避免这些辅助文本被 GPT 当成图片内容的一部分参与理解。

示例：

```text
GPT_Screenshots_20261005_013022.zip
├── resized_Screenshot_20261005_012901.heic
├── resized_Screenshot_20261005_012915.heic
├── resized_Screenshot_20261005_012930_1.heic
└── resized_Screenshot_20261005_012930_2.heic
```

ZIP 中的文件顺序应尽量保持原分享输入顺序。

每项图片必须采用 `ZipEntry.STORED`：size 与 compressedSize 相等，预先流式计算 CRC32，再流式写入；不使用 DEFLATE，不把整张编码文件读入内存。ZIP 只作为图片容器。

---

## 19. 处理状态和技术信息

处理信息可以显示在 App 内，但不得写入 ZIP。

可显示：

```text
输入图片数量
输出图片数量
失败数量

原始总大小
处理后图片总大小
ZIP 大小

处理耗时

实际 Codec
是否硬件加速
实际 bitrate mode
CQ / VBR / CBR

缩放比例
输出格式
质量
```

---

## 20. Codec 诊断页面

增加一个高级页面：

```text
编码器信息
```

尽可能通过 Android 公共 API 实际查询并显示：

```text
Android 版本
设备型号
SoC（如果能够可靠获取）

HEIC codec
HEVC codec

Codec name

Hardware Accelerated
Software Only
Vendor Codec

CQ Supported
CBR Supported
VBR Supported

Quality Range
Complexity Range

支持的宽度范围
支持的高度范围

当前选择的 codec
当前实际 bitrate mode
```

无法可靠获取的内容显示：

```text
Unknown
```

不得根据 Snapdragon 型号猜测。

---

## 21. 分享生成的 ZIP

只有桌面主界面的“分享 ZIP”按钮打开系统 Sharesheet。Android 10+ 使用 MediaStore.Downloads 的 `content://` URI；Android 9 使用限定目录的 FileProvider。通过 ClipData、ACTION_SEND 和临时读取授权，不暴露私有真实路径。系统分享进入的轻量流程保存完成后直接退出，不再弹出 Sharesheet。

最终 ZIP 也可直接从 ChatGPT 的系统文件选择器选取，不要求经 Sharesheet 导入。

---

## 22. 公共 Downloads 与清理

```text
Download/GPT Screenshot Pack/
├── Temp/<任务 UUID>/   中间图片、尚未发布的 ZIP
└── Output/            最终 ZIP
```

- Android 10+ 使用公开 MediaStore.Downloads / RELATIVE_PATH / IS_PENDING，不申请全盘权限；完整 ZIP 才移动到 Output 并发布。
- Android 9 仅使用限定 maxSdkVersion=28 的存储授权，兼容已有最低版本。
- 任务完成或失败/取消时清理本次 Temp；启动时只清理超过 24 小时的 Temp。
- Output 不自动删除，已发布后收到取消请求也保留。
- App 提供“清理临时文件”和“清理生成文件”两个独立按钮；前者不能删除 Output，后者需明确确认。
- 清理不得删除原始截图。主界面本身处理时禁用清理，清理 Temp 时跳过进程内活跃的主界面/分享任务。
- Output 清理支持自定义 ZIP 名称；Android 10+ 按 owner、精确目录及 ZIP MIME/扩展名过滤。Android 9 无 owner 列，仅清理专用 Output 中的 `.zip`，请勿放入无关 ZIP。
- ZIP 名称按分享命名确认或桌面默认时间规则生成，同名不覆盖；图片命名规则完全保持。

---

## 23. 错误处理

单张图片处理失败时默认：

```text
跳过该图片
→
继续处理剩余图片
```

完成后 App 内显示：

```text
成功：37
失败：1
```

如果所有输入均失败：

```text
不生成空 ZIP
```

并显示错误信息。

错误详情仅显示在 App 内，不加入 ZIP。

---

## 24. 处理进度界面

通过系统分享调用时，先使用独立轻量窗口确认 ZIP 名称：

```text
ZIP 文件名
[聊天记录]
[开启] 自动附加日期时间
自动补 .zip；日期格式 yyyyMMdd_HHmmss
[取消] [开始处理]
```

随后显示极简进度：

```text
正在处理图片

18 / 42

[取消]
```

不需要复杂动画。

保存成功后自动关闭分享 Activity，返回原 App，不自动打开 Sharesheet。部分成功时保存有效图片并提示数量；全部失败时保留错误窗口，可重试或关闭。

如果任务完成非常快，应避免不必要的界面闪烁。

---

## 25. 主界面建议

```text
GPT Screenshot Pack

图片处理
缩放比例          100%
输出格式          HEIC
HEIC 质量         95

编码
编码器            自动
CQ                自动

长截图
安全上限          16384 px
超过限制          自动切片

文件
文件名前缀        resized_
ZIP 名称          按生成时间

完成
桌面测试完成后    显示结果，可手动分享
系统分享完成后    保存并自动退出

[选择图片并测试]

──────────────

高级
编码器信息
清理临时文件 / 清理生成文件
关于
```

切换 PNG 时：

```text
隐藏质量设置
```

切换 HEIC/JPEG 时显示对应质量参数。

---

## 26. 手动测试功能

主界面提供：

```text
选择图片并测试
```

用户可以选择一张或多张图片。

完成后显示：

```text
输入数量
输出数量

原始总大小
输出图片总大小
ZIP 大小

总处理时间

缩放比例
输出格式
质量

实际 Codec
实际 bitrate mode
```

提供：

```text
[分享 ZIP]
```

方便以后测试：

```text
HEIC 85
HEIC 90
HEIC 95
HEIC 100
```

以及不同缩放比例对 GPT OCR 的影响。

---

## 27. 第一版明确不做

第一版不要加入：

- OCR
- AI
- ChatGPT API
- 自动上传云端
- 图片编辑器
- 手动裁剪
- 滤镜
- EXIF 编辑器
- FFmpeg
- x265
- libheif
- 自定义 QP
- CTU
- SAO
- Deblocking
- 复杂 HEVC 参数
- 数据库
- 用户账号
- 网络功能

避免把一个专用工具扩张成通用图片处理软件。

---

## 28. 工程优先级

实现时优先级为：

```text
正确性
>
文字识别质量
>
兼容性
>
内存安全
>
稳定性
>
处理速度
>
文件体积
>
高级编码参数
```

不得为了节省少量文件体积而明显降低小字识别能力。

---

## 29. 第一版验收

必须至少验证：

1. 单张 JPG → 默认保持分辨率（或所选缩放）→ HEIC → STORED ZIP 成功。
2. 多张截图通过 Android 分享菜单批量处理成功。
3. PNG 输入正常。
4. HEIC 输入正常。
5. 20～50 张截图连续处理不崩溃。
6. 约 19399 px 高的系统长截图，在默认 100% 下均匀切片；手动选择 50% 时保持单张。
7. 超过 16384 px 的最终图片可以正确自动切片。
8. 输出 HEIC 可以再次被 Android 正常解码。
9. ZIP 可以正常解压。
10. ZIP 内只包含图片。
11. 原始文件名主体得到保留。
12. 正常输出带 `resized_` 前缀。
13. 切片正确使用 `_1`、`_2`、`_3`。
14. 输入顺序得到合理保持。
15. 桌面主界面可手动通过系统 Sharesheet 分享 ZIP；分享入口保存完成后直接退出。
16. 原始截图完全不被修改。
17. App 没有网络权限。
18. 处理几十张图片不存在明显内存泄漏或文件句柄泄漏。
19. Gradle 构建成功。
20. Android Lint 没有未处理的重要错误。
21. 最终 ZIP 位于公共 Download/GPT Screenshot Pack/Output/，系统文件选择器可见。
22. 清理 Temp 不影响 Output，Output 不会自动删除。
23. ZIP 每项为 STORED，size/压缩大小/CRC32 正确，采用流式读写。
24. 默认 100%，HEIC/JPEG 的质量快捷值为 50/75/85/90/95/100，PNG 隐藏质量。

---

## 30. Codex Cloud 工作要求

Codex 开始开发前应：

1. 阅读完整规格；
2. 检查当前 AndroidX `HeifWriter`、`EncoderPreference`、`MediaCodec` 和 `ImageDecoder` 公共 API；
3. 根据真实 API 能力设计实现；
4. 优先使用稳定公开 API；
5. 不为了满足规格中的高级选项调用私有 API；
6. 如果某个要求无法通过公共 API 稳定实现，在 README 中明确记录实际差异。

开发过程中应形成完整闭环：

```text
建立项目
→ 编写实现
→ Gradle Build
→ 运行测试
→ Android Lint
→ 查看错误
→ 修复
→ 再次构建
```

项目结构应允许以后方便修改：

- 缩放比例；
- HEIC Quality；
- 硬件/软件编码器策略；
- CQ 策略；
- 16384 安全阈值；
- 自动切片逻辑；
- 输出格式；
- 文件命名规则。

## 31. v0.2.0 保留与升级规则

保留现有 HeifWriter / MediaCodec、CQ Auto、Grid Auto 和完整 Codec 诊断，不重建工程。0.1.0 实际使用稳定版 HeifWriter 1.1.0；Prefer Hardware/Software 与可选 CQ 未在旧版实现，0.2.0 不伪造这些能力，README 明确记录稳定公开 API 限制。版本升级至 0.2.0 / versionCode 2；保存的参数继续沿用，新安装或缺省设置默认 100%。若无 release signing，沿用已有 debug keystore 发布可安装 APK，并在 GitHub Release 注明。

## 32. v0.3.0 分享流程与升级规则

版本 0.3.0 / versionCode 3，基于同一项目继续修改，沿用原开发签名。分享入口不复用 MainActivity，而使用无任务亲和性的透明 ShareActivity，取消或成功后只 finish 自己，不移除来源 App 的任务。主界面和 HEIC 稳定公开 API 限制继续保留。

命名输入、日期开关、进度及权限请求在屏幕旋转时保持。任务取消在检查点响应，等待 Temp 清理后退出，已发布的 Output 保留。后台完成后回到前台再提示并关闭。不保证系统强制杀进程后恢复编码；已提交任务不自动重跑，提示用户重新分享，避免重复输出。

新增验收：单张/多张/ClipData 分享仅打开轻量命名窗；默认日期开关开启、记住确认的名称/开关、自动补扩展名；确认前不处理；旋转不重复任务；自定义 ZIP 保存到公共 Output 且为 STORED/仅图片；保存成功关闭分享 Activity、不打开主界面或 Sharesheet；全部失败显示错误；同名不覆盖；Temp 清理跳过其他活跃分享任务，Output 清理支持自定义名称。
