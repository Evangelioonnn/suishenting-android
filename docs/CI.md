# 自动构建与检查

GitHub Actions工作流为[Android CI](https://github.com/Evangelioonnn/suishenting-android/actions/workflows/android-ci.yml)，配置在[android-ci.yml](../.github/workflows/android-ci.yml)。v0.9.12 的[实际GitHub运行](https://github.com/Evangelioonnn/suishenting-android/actions/runs/43930402978)已通过，报告artifact已下载并逐份复核；测试源码提交为`a6e1718b317d474c8e044edec5a9d5df30fe56ff`。

## 自动执行的范围

- 推送main、面向main的Pull Request及手动运行时触发。只修改Markdown或`.gitignore`不重复构建。同一分支或PR有新提交时取消过时运行。
- 使用Ubuntu24.04、Temurin JDK25、项目原Gradle Wrapper及官方校验和，安装SDK36/BuildTools36.1.0。Action固定到已核对的官方commit；Gradle使用GitHub基础缓存，PR只读缓存。
- POSIX启动脚本采用同版Gradle9.5.0官方文件，保留LF和可执行模式；Wrapper JAR、分发校验和及Windows脚本保持。首次Linux实测发现旧脚本把字面引号传给Java，原失败保留；参数及含空格路径保护已本地复验，最终实际Linux完整检查通过。
- CI显式加载[官方仓库配置](../tools/ci/official-repositories.gradle)，从Maven Central、Google及Gradle Plugin Portal解析原版本依赖，避免依赖本机缓存或第三方镜像。项目的依赖版本、默认仓库配置和本机可选缓存不变；完整解析日志保存到构建报告。
- 构建Debug和未签名Release，执行`:app:testDebugUnitTest`、`:app:lintDebug`、`:app:lintRelease`。禁用任务输出缓存，现有单元测试实际执行。
- [报告检查器](../tools/ci/verify_reports.py)要求 v0.9.13 至少执行 45 个单元测试、失败/错误/跳过均为0、两套lint为0错误。缺失/损坏报告和计数不一致均失败。警告会列出，不压制或新增lint baseline。
- Actions摘要显示结果；日志、JUnit XML/HTML及lint报告保存为`android-ci-reports-运行ID-尝试号`，保留14天。构建失败也尽可能保存已有报告。

## 如何查看与复验

打开上述Actions页面，选择最新运行即可查看各步骤和摘要；在Artifacts中下载报告。手动复验可使用“Run workflow”。源码检查失败会显示失败状态；本轮不新增自动合并或主分支保护规则。

本机可执行以下命令，SDK/JDK按[README](../README.md)准备：

```powershell
.\gradlew.bat --no-daemon --no-parallel --no-build-cache --init-script tools/ci/official-repositories.gradle :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug :app:lintRelease
python tools/ci/verify_reports.py --minimum-tests 45 --output ci-reports/check-summary.json
```

CI 仅上传检查报告。用户安装包由维护者单独发布，来源及 SHA-256 随版本提供。[v0.9.13-rc.1](https://github.com/Evangelioonnn/suishenting-android/releases/tag/v0.9.13-rc.1)

这套CI覆盖构建、JVM单元和静态lint。四API instrumentation、实际手机、录音、耳机/来电、长时锁屏与真实云调用仍遵循[外部验收表](EXTERNAL_ACCEPTANCE_GUIDE.md)，不能以绿色CI替代正式验收。


## 2026-11-17 实际验收

| 项目 | 结果 |
|---|---|
| GitHub运行 | [运行43930402978](https://github.com/Evangelioonnn/suishenting-android/actions/runs/43930402978)，push触发，托管Ubuntu24.04，全部必需步骤success |
| 源码身份 | `a6e1718b317d474c8e044edec5a9d5df30fe56ff`；后续文档维护不改变这次通过的CI/构建脚本和应用源码 |
| 构建与单元 | Debug、未签名Release构建成功；现有44项JVM单元实际执行，失败/错误/跳过均0，未使用任务输出缓存 |
| Debug lint | 0错误，23条保留警告 |
| Release lint | 0错误，23条保留警告 |
| 报告 | 实际下载`android-ci-reports-43930402978-1`，校验ZIP及全部原始XML/汇总哈希，重新运行报告检查器一致；GitHub保留14天 |
| 本地守护 | 原44项报告兼容及缺失/损坏/失败/错误/跳过/数量不足/计数不符/lint错误共14组均按预期拒绝或通过 |

首轮[43926771039](https://github.com/Evangelioonnn/suishenting-android/actions/runs/43926771039)因原POSIX脚本的JVM参数字面引号而失败，改用同版本官方脚本后入口通过。第二轮[43928888358](https://github.com/Evangelioonnn/suishenting-android/actions/runs/43928888358)未能解析KSP，报告守护拒绝缺失报告；该版本经官方发布/POM确认存在，采用CI官方仓库配置后完整通过，依赖版本没有变更。原两次失败、原脚本、官方来源、参数回归和报告均由维护者保留，不以重跑成功抹去失败。

PR与手动触发已配置，但本次实际全流程证据来自push运行，不宣称三种触发均另跑一遍。应用v0.9.12/code21、Room2、原证书及已公开Release/四资产保持；CI绿色不代表原15项真实条件或全部正式验收完成。

本次在线lint各23条警告已逐项核对：16条是SDK/依赖可更新提示，另外7条为既有图标目录、空间API及KTX写法建议。相较原离线各10条，多出的13条均为在线版本提示，应用源码和依赖版本未变。本轮保留已验收依赖与实际失败保护行为，不用升级或忽略规则消除提示；以后依赖升级需另做兼容评估和回归。这些lint结果不等同于完整漏洞扫描。
