# v0.9.13-rc.1 已知观察与待验条件

- 在 Android 13 模拟器的首批 145 项回归中，一次长文编辑器搜索断言在 Espresso 读取 Compose 界面节点时遇到布局重入。相同安装包的该项两次独立重跑和 145 项整批复跑均通过，最初触发时序仍未复现。
- 在 Android 16 较早版本的历史恢复测试中出现过偶发的重试断言异常（O01）。本版 Android 16 模拟器整批通过，旧观察的根因仍待确认。[原版观察记录](https://github.com/Evangelioonnn/suishenting-android/blob/v0.9.12-rc.1/docs/KNOWN_ISSUES.md)
- 真实录音首尾、设备实际听感、耳机和来电中断、长时锁屏播放、手机上的响应时间，以及讯飞和百度各自的合成、缓存和使用记录，仍需在实际使用条件下核对。[外部验收操作表](EXTERNAL_ACCEPTANCE_GUIDE.md)

本地覆盖升级已验证相同文本的两句各自保留原录音。升级前请先处理待确认录音、导出完整备份，然后在原应用上覆盖安装。[本版验收摘要](RELEASE_STATUS.md)
