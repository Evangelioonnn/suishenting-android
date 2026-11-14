# v0.9.3-rc.1 已知边界问题

以下四项在最终候选的独立Android运行诊断中复现，尚未修复，留待下一轮。样本均为合成数据，未用用户真实文稿或账户。候选不代表无bug或全部正式验收通过。

| 编号 | 条件与实际影响 | 定位 |
|---|---|---|
| N01 / P2 | DOCX的mc:AlternateContent同时含Choice/Fallback文本框时可能重复导入相同文本；合成XML文本框出现2次。原文件保留，普通及原嵌套样本通过 | [DocumentImporter](../app/src/main/java/com/museum/reader/data/DocumentImporter.kt)，parseWordXml兼容分支选择 |
| N02 / P2 | 异常/被修改备份中，同一文稿的两条不同历史使用同一版本ID，恢复接受但2条变1条。正常UUID导出不会主动生成此重复 | [BackupStore](../app/src/main/java/com/museum/reader/data/BackupStore.kt)，版本解析及Upsert |
| N03 / P2 | 不完整备份路线引用不存在文稿时，恢复过滤该站点而未告警；合成路线2站变1站，预览/结果均零告警 | [BackupStore](../app/src/main/java/com/museum/reader/data/BackupStore.kt)，mappedIds过滤 |
| N04 / P2 | 旧数据库原已存极端超长文稿名时，列表摘要可能SQLiteBlobTooBigException。通过DAO构造750000中文字符复现；原名值保留。新入库1000字符限制和本轮旧超长路线保护已通过 | [ReaderDao](../app/src/main/java/com/museum/reader/data/db/ReaderDao.kt)，summaries直接读取title |

N01是合成兼容分支XML，未据此声称某一Word版本全部格式都有问题；N04是构造旧异常库，未发现用户资料中实际存在该情况。遇到类似情况请保留原文件/备份和应用数据，去除个人资料后反馈复现步骤，不通过卸载或清数据处理。

下一轮建议：兼容分支只选一次并保留前后文；重复历史ID提交前拒绝或无损保留；缺失路线引用明确告警/确认部分恢复；旧异常名称安全读取且保留原值。未经安排不自动实施。

工具维护建议：部分维护脚本默认证据标签仍为历史v0.9.0；使用时明确指定当前包、版本及唯一输出目录。它与上述四项运行问题分开记录。
