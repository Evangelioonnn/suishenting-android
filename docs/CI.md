# 自动构建与检查

GitHub Actions工作流为[Android CI](https://github.com/Evangelioonnn/suishenting-android/actions/workflows/android-ci.yml)，配置在[android-ci.yml](../.github/workflows/android-ci.yml)。本轮首次实际运行结果尚待完成；完成后补充证据。

## 自动执行的范围

- 推送main、面向main的Pull Request及手动运行时触发。只修改Markdown或`.gitignore`不重复构建。同一分支或PR有新提交时取消过时运行。
- 使用Ubuntu24.04、Temurin JDK25、项目原Gradle Wrapper及官方校验和，安装SDK36/BuildTools36.1.0。Action固定到已核对的官方commit；Gradle使用GitHub基础缓存，PR只读缓存。
- 构建Debug和未签名Release，执行`:app:testDebugUnitTest`、`:app:lintDebug`、`:app:lintRelease`。禁用任务输出缓存，现有单元测试实际执行。
- [报告检查器](../tools/ci/verify_reports.py)要求至少44个单元测试、失败/错误/跳过均为0、两套lint为0错误。缺失/损坏报告和计数不一致均失败。警告会列出，不压制或新增lint baseline。
- Actions摘要显示结果；日志、JUnit XML/HTML及lint报告保存为`android-ci-reports-运行ID-尝试号`，保留14天。构建失败也尽可能保存已有报告。

## 如何查看与复验

打开上述Actions页面，选择最新运行即可查看各步骤和摘要；在Artifacts中下载报告。手动复验可使用“Run workflow”。源码检查失败会显示失败状态；本轮不新增自动合并或主分支保护规则。

本机可执行以下命令，SDK/JDK按[README](../README.md)准备：

```powershell
.\gradlew.bat --no-daemon --no-parallel --no-build-cache :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug :app:lintRelease
python tools/ci/verify_reports.py --minimum-tests 44 --output ci-reports/check-summary.json
```

CI不需要云服务密钥或本机签名，不自动创建Release，也不上传APK作为升级包。现有v0.9.12-rc.1及四个发布文件保留，应用版本/数据库/原签名不变；普通用户继续从[Release](https://github.com/Evangelioonnn/suishenting-android/releases/tag/v0.9.12-rc.1)下载原验收包。

这套CI覆盖构建、JVM单元和静态lint。四API instrumentation、实际手机、录音、耳机/来电、长时锁屏与真实云调用仍遵循[外部验收表](EXTERNAL_ACCEPTANCE_GUIDE.md)，不能以绿色CI替代正式验收。
