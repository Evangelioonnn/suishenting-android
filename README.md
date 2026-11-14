# 随身听

用于讲解词阅读和逐句练习的 Android 应用，最低支持 Android 8.0（API26）。

**[下载 APK](https://github.com/Evangelioonnn/suishenting-android/releases/download/v0.9.4-rc.1/Suishenting-v0.9.4-rc.1.apk)** · [全部文件及版本说明](https://github.com/Evangelioonnn/suishenting-android/releases/tag/v0.9.4-rc.1) · [历史版本](https://github.com/Evangelioonnn/suishenting-android/releases)

当前为 **v0.9.4-rc.1 预发布修复候选**，应用 v0.9.4/code13，Room2，原证书。本轮四项修复及本机验收完成；新发现的两项备份元数据边界问题、一次 API36 待观察记录和真实设备/服务待验条件见[验收摘要](docs/RELEASE_STATUS.md)与[已知问题](docs/KNOWN_ISSUES.md)。尚未完成全部正式验收。

## 安装和更新

先在旧版导出完整备份、处理待确认录音，再下载 APK 直接覆盖安装，**不要卸载旧版或清除应用数据**。更新后抽检常用文稿和既有录音。源码 ZIP 用于开发。若提示证书不兼容，保留原应用和资料后反馈。

已在独立模拟器实际完成 v0.8.0/v0.9.3 同证书覆盖升级，合成文稿、稳定句 ID、录音 SHA、重点、路线、历史、草稿、设置和位置保持；含四类 750000 字旧名称及坏历史的原值也保留。模拟器证据不能替代每台真实手机的检查。

## 本轮修改

- DOCX 兼容分支只选一个可读分支，保留外层前后文；空 Choice 可采用文字 Fallback，合法重复正文不按文字去重。
- 备份历史 ID 全局唯一，重复时提交前明确拒绝；缺失路线引用在预览及结果告警，明确确认后才部分恢复。
- 旧异常超长文稿、历史、草稿名安全读取并保留完整原值；只改其他字段不截名。显式历史修名只改名称，失败在当前窗重试；全部修正后可导出及空环境恢复。

剪贴板/分享/TXT/DOCX 导入、稳定句身份、独立播放会话、全文/范围循环、搜索、重点句、路线、本人录音/云缓存/系统 TTS、独立草稿、最近5版历史、完整备份、最近删除和存储维护等能力继续保留。见[使用说明](docs/USAGE.md)和[修改日志](CHANGELOG.md)。

文稿默认本机保存；主动生成或试听云声音会把对应文字发给选定的讯飞或百度。密钥由 Android Keystore 本机加密保存，不进入备份；只在手机应用配置，不上传到 Issues。取消不能撤回服务商已处理请求，本机用量不等于账单。

## 验证和源码构建

42 单元、四 API 各87主回归（348次）、50专项、真实进程中断、两种覆盖升级和实际 UI/空恢复通过；320原图/43联系表复核。两套 lint 各0错误/10保留警告。API36首批一次失败完整保留，原方法与整批复验通过，原因尚未确认。新问题未记作通过，详见验收摘要。

SDK36 / Build Tools36.1.0 / Gradle9.5.0 / AGP9.3.1 / Compose插件2.2.10 / KSP2.2.10-2.0.2 / Room2.8.4。已验证运行 JDK25.0.2，Java/Kotlin 输出目标17。配置本机 SDK 后执行：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:assembleRelease :app:lintRelease
```

macOS/Linux 使用 `./gradlew`。首次构建需要网络；离线需完整缓存。可选本地 Maven 通过 `MUSEUM_READER_LOCAL_MAVEN` 或 `museumReader.localMaven` 指定，不要求固定盘符。Release 默认未签名；仓库不含签名私钥，自行不同证书无法覆盖发布包。

包含全部跟踪业务、单元及主仪器测试、Wrapper、Room1/2 schema 和维护工具；独立补充/诊断来源和原始设备证据由维护者本机保留。三个观察方法执行成功只表示完成观测，不代表两项已知问题关闭。[外部验收操作表](docs/EXTERNAL_ACCEPTANCE_GUIDE.md)保留真实手机和两家服务待验条件。

APK SHA-256：`EC526C8474D1009CC5166A1B62D0D7756E9649FEFA6074D2B54F9139689B9F07`。Release 同时提供源码 ZIP、`release-provenance.json` 和 `SHA256SUMS.txt`；安装包与本机固定验收原包一致，未因发布重打或重签。
