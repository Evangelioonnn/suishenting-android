# 随身听

[![Android CI](https://github.com/Evangelioonnn/suishenting-android/actions/workflows/android-ci.yml/badge.svg?branch=main)](https://github.com/Evangelioonnn/suishenting-android/actions/workflows/android-ci.yml)

随身听是一款 Android 讲解词阅读和逐句练习应用。你可以导入文稿、逐句录下自己的声音，再按原顺序或练习路线播放。支持 Android 8.0 及以上。

**[下载 v0.9.13-rc.1 安装包](https://github.com/Evangelioonnn/suishenting-android/releases/download/v0.9.13-rc.1/Suishenting-v0.9.13-rc.1.apk)** · [版本说明与校验文件](https://github.com/Evangelioonnn/suishenting-android/releases/tag/v0.9.13-rc.1) · [对应源码 ZIP](https://github.com/Evangelioonnn/suishenting-android/releases/download/v0.9.13-rc.1/Suishenting-v0.9.13-rc.1-source.zip) · [历史版本](https://github.com/Evangelioonnn/suishenting-android/releases)

## 安装与更新

首次使用请下载以 `.apk` 结尾的文件，在手机上打开安装。已有旧版时，请先处理待确认的录音，从设置导出完整备份，再打开 APK 选择“更新”。覆盖安装会沿用原应用数据。若手机提示签名不一致，请保留原应用和数据。

本版沿用原签名，应用版本为 0.9.13（22）。在 Android 9 模拟器从 v0.9.12 实际覆盖更新后，两句相同文字各自的不同录音仍对应原句，两份音频逐文件校验一致。常用手机、耳机及真实云服务还有 15 项待验，本版作为预发布供试用。[验收摘要](docs/RELEASE_STATUS.md)

## 本版变化

- **录音**。重新打开录音页可以看到录制时的原句。重复保存或随后到达的放弃操作会核对录音身份。遇到损坏记录时，可在存储维护中先另存原件，再确认隔离，备份会列出遗漏。
- **编辑与导入**。把正文改回原文再退出，会清理本次产生的旧草稿。导入长文可以翻页校对；旋转屏幕、在大字号横屏下弹出键盘或创建失败后重试，均保留已输入的修订。
- **云声音**。讯飞或百度已返回声音后，如果手机写盘或入库失败，应用会提示检查存储，结束本次生成。

文稿、草稿和本人录音默认保存在手机上。主动生成或试听云声音时，相应文字会发送给所选服务商。云密钥仅在应用中配置，由 Android Keystore 加密保存，完整备份不包含密钥。[使用说明](docs/USAGE.md) · [修改日志](CHANGELOG.md)

## 从源码构建

安装 JDK 和 Android SDK。项目使用 Gradle Wrapper 9.5.0、AGP 9.3.1、KSP 2.2.10-2.0.2、Room 2.8.4、SDK 36 和 Build Tools 36.1.0。CI 使用 JDK 25，Java/Kotlin 编译目标为 17。

~~~powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:assembleRelease :app:lintRelease
~~~

在 macOS 或 Linux 上使用 `./gradlew`。首次构建需要网络。可选 Maven 缓存由 `MUSEUM_READER_LOCAL_MAVEN` 或 `museumReader.localMaven` 指定。Release 构建默认未签名；覆盖安装已发布的应用需要原证书。[自动检查](docs/CI.md)

源码包包含业务源码、跟踪的单元测试及 Android 测试、Gradle Wrapper、Room 1/2 schema 和维护工具。Release 中的 `release-provenance.json` 记录公开提交、文件清单和构建来源，`SHA256SUMS.txt` 提供安装包、源码包及来源清单的校验值。
