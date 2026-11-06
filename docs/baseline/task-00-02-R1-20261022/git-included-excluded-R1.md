# Git纳入与排除

关口C：BLOCKED_BACKUP_GATE
精确暂存白名单和Git退出码见git-whitelist-R1.json、git-commands-R1.json（如已执行）。不执行整仓git add。

## 先前10项逐项分类
| 路径 | 本地审查 | Git策略 |
|---|---|---|
| `README.md` | DEMO_CONSTANT_REVIEW | ALLOW_ONLY_AFTER_LOCAL_CONTENT_REVIEW |
| `backend/src/main/java/com/footballref/assist/config/DataSeeder.java` | DEMO_CONSTANT_REVIEW | ALLOW_ONLY_AFTER_LOCAL_CONTENT_REVIEW |
| `backend/src/main/resources/application-mysql.yml` | NEEDS_LOCAL_CONFIG_CONFIRMATION | EXCLUDE_CONFIG |
| `backend/src/main/resources/application.yml` | NEEDS_LOCAL_CONFIG_CONFIRMATION | EXCLUDE_CONFIG |
| `backend/src/test/java/com/footballref/assist/BackendApiSmokeTest.java` | DEMO_CONSTANT_REVIEW | ALLOW_ONLY_AFTER_LOCAL_CONTENT_REVIEW |
| `docs/demo_runbook.md` | DEMO_CONSTANT_REVIEW | ALLOW_ONLY_AFTER_LOCAL_CONTENT_REVIEW |
| `docs/manual_annotation_workflow.md` | DEMO_CONSTANT_REVIEW | ALLOW_ONLY_AFTER_LOCAL_CONTENT_REVIEW |
| `frontend/src/pages/LoginPage.vue` | DEMO_CONSTANT_REVIEW | ALLOW_ONLY_AFTER_LOCAL_CONTENT_REVIEW |
| `scripts/check-frontend-layout.mjs` | DEMO_CONSTANT_REVIEW | ALLOW_ONLY_AFTER_LOCAL_CONTENT_REVIEW |
| `scripts/smoke-system.ps1` | DEMO_CONSTANT_REVIEW | ALLOW_ONLY_AFTER_LOCAL_CONTENT_REVIEW |

八份演示常量文件已由自动种子、测试和文档交叉证明；这不是生产部署安全证明。两份配置的备份方式由本机窗口确认，但实例配置始终排除Git；配置恢复依赖受限独立备份，不宣称仅靠Git可恢复生产环境。

## Git白名单

## Git排除

未纳入配置实例、秘密、数据库、素材、权重、output或报告内私密输入。前端brand图像属于实际引用的应用静态资源，不属于比赛素材。
.gitignore仅在B和本机作者确认均通过后最小追加；换行转换仅以Git命令临时core.autocrlf=false关闭，保证归档字节一致，不改全局配置。