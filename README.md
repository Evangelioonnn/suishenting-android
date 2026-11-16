# 随身听

[![Android CI](https://github.com/Evangelioonnn/suishenting-android/actions/workflows/android-ci.yml/badge.svg?branch=main)](https://github.com/Evangelioonnn/suishenting-android/actions/workflows/android-ci.yml)

用于讲解词阅读和逐句练习的 Android 应用，支持 Android 8.0（API26）及以上。

**[下载 APK](https://github.com/Evangelioonnn/suishenting-android/releases/download/v0.9.12-rc.1/Suishenting-v0.9.12-rc.1.apk)** · [版本说明和校验文件](https://github.com/Evangelioonnn/suishenting-android/releases/tag/v0.9.12-rc.1) · [历史版本](https://github.com/Evangelioonnn/suishenting-android/releases)

当前为 **v0.9.12-rc.1 本机收敛预发布候选**，应用v0.9.12/code21、Room2，沿用原证书。七轮检查、复现、修复及最后两轮互补复查后，未发现新的可确认、可独立修复的必要缺陷。真实设备/服务验收尚未全部完成，API36历史偶发观察仍保留；详情见[验收摘要](docs/RELEASE_STATUS.md)和[待验条件](docs/KNOWN_ISSUES.md)。

## 安装和更新

先在旧版导出完整备份、处理待确认录音，再直接覆盖安装APK，**不要卸载旧版或清除应用数据**。更新后抽检常用文稿和既有录音。源码ZIP供开发；若提示签名不兼容，保留原应用和资料后反馈。

已在独立模拟器实际完成v0.8.0和公开v0.9.4同证书覆盖升级，合成资料的文稿、稳定句ID、录音SHA、重点、路线、历史、草稿、设置及旧异常数据保持。这些结果不能替代每台真实手机的验收。

## 本轮改进

- 历史、草稿、重点和路线完整参与备份冲突；默认保留本机，另存独立，明确覆盖先留恢复点，预览后变化须重新确认。
- 修复编号撞号、历史/路线输入校验、设置/编辑/清理并发保护，以及重点和路线位置事务问题。
- 语音分段保留补充汉字及表情；HTTP先分类并限制响应；新录音确认和旧文件找回使用统一录音身份。
- 坏音频导出保留原字节并明确警告；平台拒绝播放启动后，下一次明确播放可重试。

继续提供剪贴板/分享/TXT/DOCX导入、独立播放、全文/范围循环、搜索、重点句、路线、本人录音/云缓存/系统声音、独立草稿、最近5版历史、完整备份及存储维护。见[使用说明](docs/USAGE.md)与[修改日志](CHANGELOG.md)。

文稿默认本机保存；主动生成或试听云声音会将对应文字发送给选定的讯飞或百度。云密钥由Android Keystore本机加密保存，不进入备份。只在手机应用配置，不上传聊天或Issues。取消不能撤回提供商已处理的请求，本机计数不等于账单。

## 验证与构建

[自动检查](docs/CI.md)会在main源码推送、Pull Request或手动运行时构建Debug/未签名Release，执行现有单元和两套lint，并保存检查报告。CI不自动发布APK，已有v0.9.12-rc.1及原四资产保持。

最终固定包通过44单元、四API各134主回归（536次）、50专项、真实进程中断、两种实际覆盖升级、系统备份/空恢复和实际TalkBack；355张原图复核，双lint各0错误/10保留警告。最后源码/边界与实际流程/异常复查均无新确认必要缺陷；全部29个原失败样例和中间候选本机保留。未以模拟器代替真实声音、云服务或手机性能验收。

SDK36 / Build Tools36.1.0 / Gradle9.5.0 / AGP9.3.1 / Compose插件2.2.10 / KSP2.2.10-2.0.2 / Room2.8.4。验证运行JDK25.0.2，Java/Kotlin输出目标17。配置本机SDK后运行：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:assembleRelease :app:lintRelease
```

macOS/Linux使用`./gradlew`。首次构建需要网络，离线需完整缓存。可选Maven缓存由`MUSEUM_READER_LOCAL_MAVEN`或`museumReader.localMaven`指定。Release默认未签名；仓库不含签名私钥，自行不同证书不能覆盖本发布包。

公开完整业务、全部跟踪单元及主仪器测试、Wrapper、Room1/2 schema和维护工具。独立补充测试/诊断与原始设备证据由维护者本机保留，来源另有登记。[外部验收操作表](docs/EXTERNAL_ACCEPTANCE_GUIDE.md)保留原15项真实条件。

APK SHA-256：`B55F333FF5750EABFEEC4AA45DF548D92370EFA2878321E69C78D15F6FC366D2`。Release另提供与公开tag一致的源码ZIP、`release-provenance.json`及`SHA256SUMS.txt`；APK与本机最终固定原包一致，未因公开分发重打或重签。
