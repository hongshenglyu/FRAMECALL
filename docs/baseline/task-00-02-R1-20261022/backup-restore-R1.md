# 源码备份与恢复演练

关口B：PARTIAL_BACKUP_ONLY
项目外受限目录：`<PROJECT_PARENT>/FRAMECALL-backups/20261022-R1-7f0ad0bf08`。
实际备份114项，恢复114项；普通ZIP成员114项，专用受限保密副本0项。
普通源码ZIP：source-safe.zip；保密配置（仅本机明确批准时）在confidential-originals中，绝不放入普通ZIP或Git。
恢复目录：`<PROJECT_PARENT>/FRAMECALL-backups/20261022-R1-7f0ad0bf08/restore-source-proof`；从实际ZIP/保密副本恢复，逐项源/备份/恢复SHA256见backup-manifest-R1.csv。
CRC：True；源码ZIP大小：2244927字节。

## 必需关键文件恢复
| 文件 | 结果 |
|---|---|
| `frontend/src/pages/LoginPage.vue` | PASS |
| `frontend/src/pages/WorkbenchPage.vue` | PASS |
| `frontend/public/brand/logo.svg` | PASS |
| `backend/src/main/java/com/footballref/assist/FootballRefAssistApplication.java` | PASS |
| `backend/src/main/java/com/footballref/assist/service/TaskService.java` | PASS |
| `backend/src/test/java/com/footballref/assist/BackendApiSmokeTest.java` | PASS |
| `ai-service/app/main.py` | PASS |
| `ai-service/app/analyzer.py` | PASS |
| `ai-service/app/detector.py` | PASS |
| `ai-service/app/tracker.py` | PASS |
| `ai-service/tests/test_contracts.py` | PASS |
| `scripts/run-system.ps1` | PASS |

未经授权的配置：['backend/src/main/resources/application-mysql.yml', 'backend/src/main/resources/application.yml']
源哈希不冒充备份哈希，三列独立计算。没有启动恢复副本。视频、storage、数据库、权重及历史output未复制或恢复，不称完整系统可恢复。

Git恢复关口：False；从HEAD只读archive导出至独立restore-git-proof，核验0个实际提交文件；没有在原项目checkout/reset恢复文件。