## 24. V2.3新增｜2026-10-24 01-04验收与01-05启动

### 24.1 01-04验收事实

- 用户本机Codex执行并上传`task-01-04-20261024-reports.zip`；ChatGPT在聊天容器解压54件ZIP（CRC通过），直接复算`final-validation.json`列出的53件交付文件大小与SHA256，全部一致；清单自身独立排除。
- 最终`acceptance-result.json`：Maven退出码0，JUnit 43项中42 PASS/1 SKIP（跳过的是Windows媒体符号链接创建测试），0 FAIL/ERROR；新`SecurityBootstrapTest`11项全通过，原授权、媒体、内部鉴权等测试保持通过。中途first、corrected、final、verified阶段有失败，全部保留，验收只针对最后有效的acceptance结果。
- `ai-final-result.json`：Python19项中18 PASS/1 SKIP（输出符号链接试验），0 FAIL/ERROR；AI真实本机测试服务器HTTP 72/72 PASS，媒体实际HTTP 78 PASS/1 NOT_RUN，内部Java13条PASS，浏览器Chrome对12秒合成MP4执行24项PASS，前端Vite构建退出0。255行综合测试矩阵为247 PASS、8 NOT_RUN（跨层指标重复不可累加）。
- 实际15份改动（11既有、4新增），含`DataSeeder.java`、`SecureEnvironmentPostProcessor.java`、`JwtService.java`、Spring启动注册与POM、SecurityBootstrapTest、LoginPage.vue、AI service secret校验及相关脚本/说明。报告只包含相对路径/行区间/哈希，不含完整现行源码。
- 默认及生产启动不再生成固定管理员；显式初始管理员需双开关、外部凭据和空账户表；demo须唯一隔离profile和内存H2。必要`FRAMECALL_*`安全配置缺失/不合格会提前拒绝，不可退回旧静态值；JWT验证强化；实例YAML从Maven资源复制排除。**这些为本机隔离测试证据，不是生产现场验证。**
- 仍未检查/轮换原业务库历史账户、数据库/JWT/AI服务真实密钥；实际部署需要外置非秘密配置与安全密钥，本轮没执行生产Jar、TLS、MySQL与长片回归。Windows symlink两项SKIP。不得宣称生产已安全迁移；G1不放行。

### 24.2 状态与不可改变规则

- WBS已完成：`00-01`～`00-06` + `01-01`～`01-04`，共10项（均是对应范围的限定验收）。
- 当前唯一执行：`01-05`「重构独立异步调度及幂等重试」，P1，原截止2026-11-14，**进行中0%仅准备指令**，尚没有本机执行、独立异步及幂等证明；其余63项未开始。
- G0限定治理通过；G1仍未通过（`01-05/06/07`及生产边界、并发/终态仍需证据）。
- `01-01`～`01-04`的未提交代码和新增文件不能由全仓库reset/clean覆盖；任何操作先现场`git status`并保护after SHA。
- 现存媒体、数据库与外部比赛完整录像异盘灾备R15未解除；开题P24工作基准已确认字节一致，但学校/导师正式签署与录像授权仍待核实。
- 真实研究主线保持不变：真实YOLO球员检测、ByteTrack连续轨迹、接触候选与时间匹配、可播放真实剪辑、6—10场比赛标注与完整实验；Mock不可冒充真实成果。

### 24.3 01-05下一执行与验收范围

- 在Codex可访问的本地`docs/project/plan/`同步**配对V2.3 `.md`和`.xlsx`**；只读取最新源码/00-05草案/00-06规约，并执行`FRAMECALL_WBS_01-05_Codex执行提示词_20261024.md`。
- 目标：独立线程池/调度组件而不是同类`@Async`自调用，任务创建/合法重试快速响应；attemptId真实关联，针对同task并发重试采用DB原子约束或等效事务级防重；重试允许状态、失败、满载、异常、跨用户拒绝等负向测试。
- 必须有至少20个并发重试请求以及实际工作线程/DB记录数；重跑01-01～04及Python/Chrome回归；不要自称实现01-06取消优先、01-07复核生命周期、跨机器exactly-once或真实AI。
- 只使用隔离H2、随机合成安全配置及回环服务；不读取/轮换生产秘密、不能触碰真实业务数据库。交脱敏`task-01-05-20261024-reports.zip`后由ChatGPT独立验收，不自动启动01-06或更新G1。

### 24.4 附件与交接

- 验收报告：`FRAMECALL_WBS_01-04_最终验收结论_20261024.md`。
- 下一Codex指令：`FRAMECALL_WBS_01-05_Codex执行提示词_20261024.md`。
- 新计划：`FRAMECALL_毕业设计总计划_V2.3_20261024.md`及同名`.xlsx`。
- 源证据：`task-01-04-20261024-reports.zip`（54项含多次失败阶段与最终日志）；此前V2.2全量审计与基线见跨窗口ZIP。

