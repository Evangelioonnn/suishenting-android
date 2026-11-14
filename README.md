# 随身听

面向讲解词阅读和逐句练习的Android应用，支持本人录音、本地朗读、编辑与备份。最低支持 **Android 8.0（API26）**。

## 下载与更新

**[下载APK安装包](https://github.com/Evangelioonnn/suishenting-android/releases/download/v0.9.3-rc.1/Suishenting-v0.9.3-rc.1.apk)** · [版本说明和全部文件](https://github.com/Evangelioonnn/suishenting-android/releases/tag/v0.9.3-rc.1) · [历史版本](https://github.com/Evangelioonnn/suishenting-android/releases)

当前为 **v0.9.3-rc.1 修复候选**，应用内版本v0.9.3/code12，Room2，沿用原证书。本轮六项修复及本机验收完成；真实手机、真实云服务和手机性能仍待验，全局复查另有[四项边界问题](docs/KNOWN_ISSUES.md)待下一轮。详见[验收摘要](docs/RELEASE_STATUS.md)。

1. 在旧版应用中先导出完整备份，处理待确认录音。
2. 下载APK并直接覆盖安装，**不要卸载旧版或清除应用数据**。源码ZIP用于开发，不是安装包。
3. 更新后抽检常用文稿与既有录音。如果提示签名不兼容，保留原应用和资料，在[Issues](https://github.com/Evangelioonnn/suishenting-android/issues)反馈。

已在独立模拟器实际完成v0.8.0和v0.9.2同签名覆盖升级，合成资料的文稿、稳定句ID、音频哈希、重点、路线、历史、设置及位置保留；含旧异常路线与历史的资料也作了保护检查。此证据不能替代每台真实手机及真实录音的检查。

## 本版修复

- 备份预览识别录音/云缓存差异，默认保留本机音频；明确覆盖前保留恢复点。
- 重复另存不覆盖已编辑、有新草稿或录音的副本；边界标题保留完整字符，完全相同副本复用。
- DOCX嵌套文本框保留外层前后文；恢复后返回有效列表，避免空阅读页。
- 拒绝缺字段或坏引用的历史快照；既有坏历史告警并保护旧音频。
- 名称入库/备份/导出限制一致，旧超长路线安全读取，组织操作保留原名称。

## 日常能力

剪贴板、分享、TXT/DOCX导入；独立播放会话、全文/范围循环、搜索、重点句、路线与继续位置；本人录音、云缓存、系统TTS；独立草稿与最近5版历史；完整ZIP备份、旧格式恢复、最近删除及受保护存储维护。详细操作见[使用说明](docs/USAGE.md)与[修改日志](CHANGELOG.md)。

文稿默认保存于本机。主动生成或试听云声音会将相应文字发送给选定的讯飞或百度，需要自行配置有效账户。密钥由Android Keystore加密保存在手机，不进入备份；不要上传密钥、个人文稿或录音到Issues。取消不能撤回服务商已处理的请求，本机字数统计不是实际账单。

## 从源码构建

包含全部应用及现有跟踪单元/仪器测试、Room v1/v2 schema、Gradle Wrapper与维护工具。使用SDK36、Build Tools36.1.0、Gradle9.5.0、AGP9.3.1、Compose插件2.2.10、KSP2.2.10-2.0.2、Room2.8.4。已验证运行JDK为OpenJDK25.0.2，Java/Kotlin输出目标17。

在Android Studio配置SDK，或通过本机`local.properties`/`ANDROID_HOME`指定后执行：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:assembleRelease :app:lintRelease
```

macOS/Linux使用`./gradlew`。首次构建需要网络；离线需要完整依赖及Gradle缓存。可选本地Maven通过`MUSEUM_READER_LOCAL_MAVEN`或`museumReader.localMaven`指定，不要求固定盘符。Release默认未签名，仓库不含签名私钥；自行签名证书不同时无法覆盖已安装的发布包。

37项单元及四API各76项主回归、50专项、真实进程恢复、两种升级和340张原图复核已完成。独立补充/诊断源码及原始设备证据保留在维护者本机，公开仓库包含主测试来源；没有将观察型诊断成功视为已修复四个新问题。[外部验收操作表](docs/EXTERNAL_ACCEPTANCE_GUIDE.md)保留真实手机及服务的待验条件。

APK SHA-256：`1F27A8F43F9D11D2AA445BEC0158928D2AAFD7AE346CA57EB589CD9762A5C618`。Release还提供完整源码ZIP、`release-provenance.json`与`SHA256SUMS.txt`；源码与安装包构建来源逐文件核对，生产没有因公开说明而重打或重签。
