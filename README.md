# 随身听

面向讲解词阅读和逐句练习的 Android 应用，支持逐句朗读、本人录音、文稿编辑与备份恢复。最低支持 **Android 8.0（API 26）**。

## 下载与安装

**[下载 Android 安装包（APK）](https://github.com/Evangelioonnn/suishenting-android/releases/download/v0.9.2-rc.1/Suishenting-v0.9.2-rc.1.apk)** · [版本说明与全部下载文件](https://github.com/Evangelioonnn/suishenting-android/releases/tag/v0.9.2-rc.1) · [所有版本](https://github.com/Evangelioonnn/suishenting-android/releases)

当前为 **v0.9.2-rc.1 预发布版**，应用内版本是 v0.9.2 / versionCode 11。本机改造与回归已完成，真实手机声音、录音、耳机/来电、长时播放、手机性能及讯飞/百度真实账户验证仍待完成。详细状态见[验收摘要](docs/RELEASE_STATUS.md)。

1. 已安装旧版时，先在应用设置中导出完整备份，有待确认录音时先处理。
2. 下载 APK，打开后按 Android 提示允许该来源安装，直接覆盖更新。
3. 更新时保留原应用与数据，**不要先卸载，也不要清除应用数据**。如果系统提示签名不兼容，请保留原应用并在[Issues](https://github.com/Evangelioonnn/suishenting-android/issues)反馈。
4. 更新后检查常用文稿与已有录音。系统声音需要手机具备可用的中文 TTS；有效本人录音和已生成缓存可离线播放。

已在独立模拟器完成原 v0.8.0 同签名覆盖升级，合成资料中的文稿、句子ID、音频文件、重点、路线、历史、设置与位置保留。此结果不能替代每台真实手机和既有资料的检查。

## 主要功能

- 从剪贴板、分享、TXT、DOCX 创建文稿；支持 UTF-8、UTF-16、GB18030/GBK，正文上限100万UTF-16字符。
- 独立播放会话、暂停定位、全文/范围循环、手动浏览与恢复跟随。
- 正文查找、上下处、重点句、路线顺序与继续位置。
- 本人录音、云缓存和系统声音的选择与受控回退，支持本文倍率和全局默认。
- 独立编辑草稿、最近5版历史、完整原录音与试听确认后替换。
- 完整ZIP备份、旧格式恢复、冲突预览、覆盖前恢复点和至少7天的最近删除。
- 窄屏/横屏/大字号布局，状态语义与TalkBack主流程支持。

详细操作见[使用说明](docs/USAGE.md)，版本变更见[修改日志](CHANGELOG.md)。

## 联网与隐私

文稿默认保存在本机。主动生成或试听云声音时，会将相应文字发送给选定的讯飞或百度服务，需要自行配置有效账户。密钥由 Android Keystore 加密保存在手机，不进入应用备份。

取消会停止本机后续处理，但服务商可能已经处理或计费；本机字数计数不是实际账单。反馈问题时请保留复现步骤并去除密钥、个人文稿和录音。

## 从源码构建

工程包含应用、全部现有单元/仪器测试、Room迁移schema、Gradle Wrapper及维护工具。构建需要 Android SDK 36、Build Tools 36.1.0和可运行 Gradle 9.5.0的JDK；Java/Kotlin输出目标为17。已验证环境为 OpenJDK 25.0.2。插件和依赖版本已固定：AGP9.3.1、Compose插件2.2.10、KSP2.2.10-2.0.2、Room2.8.4。

在 Android Studio 配置 SDK，或通过本机 `local.properties`/`ANDROID_HOME` 指向 SDK 后执行：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:assembleRelease :app:lintRelease
```

macOS/Linux 使用 `./gradlew`。首次构建需要网络下载依赖；离线构建需要事先准备完整缓存。可选本地Maven目录通过 `MUSEUM_READER_LOCAL_MAVEN` 或 `museumReader.localMaven` 指定，不要求固定盘符。

Release 默认未签名。这里提供的 APK 沿用既有证书；仓库不含签名私钥。自行签名产物的证书不同时，不能直接覆盖已安装的发布包，请在独立测试设备验证。

## 校验与测试状态

APK SHA-256：

```text
F5A51E07BC34257D94893CCD13AB601927C6442F8DD6819C962D9B30E362040D
```

Release 同时提供源码ZIP、`release-provenance.json`与`SHA256SUMS.txt`。公开仓库是当前源码快照，生产代码与已验证APK构建来源逐文件核对一致；测试观察器和发布说明的来源分别登记。

36项单元测试与四个平台（API26/28/33/36）各65项主回归通过，Debug/Release lint均0错误、10个已说明警告。通过范围和待验项目见[验收摘要](docs/RELEASE_STATUS.md)；参与真机验证可参考[外部验收操作表](docs/EXTERNAL_ACCEPTANCE_GUIDE.md)。
