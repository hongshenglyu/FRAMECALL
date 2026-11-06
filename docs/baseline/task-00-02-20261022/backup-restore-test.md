# 项目外源码恢复演练

状态：BLOCKED，未执行。

已创建项目外的新空目录，路径见preflight.json的backupLocation。ACL设置/校验命令退出1332，权限限制没有得到验证；未创建source-safe.zip、未复制任何源码、未创建恢复副本、未执行CRC或恢复SHA256验证。

候选91项、保守排除10项；backup-manifest.csv的NOT_COPIED表示未复制，EXCLUDED表示安全排除，所有哈希仅属于源文件。部分业务源码存在潜在凭据字面量而被保守排除，完整源码备份范围也尚未满足。

Git作者姓名和邮箱均未配置。本地Git基线未建立，Git归档恢复也未执行。不得据本报告宣称源码已备份或项目可恢复。
