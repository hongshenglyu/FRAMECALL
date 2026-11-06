# 01-05 实施方案与文件白名单

2026-10-24。实施前决策；测试结果另见实际运行记录。

采用数据库任务行悲观写锁+短事务分配attempt、有界进程内执行器；废弃TaskService的Async自调用路径。锁内核验状态与active attempt并创建持久记录，提交后入队；不混用先查后写或内存锁作为幂等保证。原有创建入口保留；首次仅PENDING且无currentAttemptId可启动，重试仅FAILED/CANCELLED且无active attempt可启动。重复请求409，满载503并持久FAILED。未提供Idempotency-Key重放缓存，只约束同task一个活动attempt；不同task的重复上传不去重。

TaskAttempt记录UUID主键、taskId、status、active、createdAt/startedAt/finishedAt、errorCode、ownerInstance；任务currentAttemptId和事件attemptId为新增可空字段，历史不伪造attempt。TaskAttempt使用标量taskId，避免改变01-07删除级联策略，删除后的attempt保留为孤立审计记录，后续专项治理。

执行器默认2工作线程、16等待容量，固定上界，拒绝不借用HTTP线程运行。AI调用在事务外；开始与完成在各自短事务中核对currentAttemptId和active，迟到结果不落库。回调grant绑定attempt并通过服务器request attribute传播，在更新事务中再次匹配。异常只持久确定性错误码，不回显异常栈或路径。

每个attempt使用tasks/task-ID/attempts/UUID独立产物目录。AI只增加这个严格形状的路径白名单，原task目录仍兼容；不改schema、模型、评分或Mock/Real合同。历史事件与复核只保留不删除，事件响应增加attemptId；旧事件继续可读。API暂不做历史分组展示，留给01-07。

部署范围限定单实例进程内队列。启动发现遗留active attempt时隔离为FAILED/RECOVERY_REQUIRED，但保留active占位阻止自动重试；必须由运维确认旧进程和AI工作均停止后制定人工恢复。不得启动第二个业务实例共享此库；不声称持久队列、故障自动恢复或跨进程exactly-once。01-06取消优先/完整终态竞态仍需专项。

HTTP I/O结果未知（含超时/断联，保守包括连接拒绝）记录FAILED、errorCode=AI_OUTCOME_UNKNOWN、stage=RECOVERY_REQUIRED，active仍为true阻止重试；此时active表示安全占位，不表示线程仍在运行。明确503响应/本地处理异常可记录非active失败，无自动503重试。AI读超时可配置50～7200000毫秒，默认两小时；本轮故障测试使用1200毫秒。

## 允许文件

backend主代码：TaskService、AnalysisDispatcher（新增）、AnalysisTask、TaskAttempt（新增）、AnalysisTaskRepository、TaskAttemptRepository（新增）、DisputeEvent、AiClient、InternalCallbackService、InternalController、TaskController、EventController。

测试：TaskSchedulingTest（新增）；ResourceAuthorizationTest只调整合法终态重试夹具及异步等待，不删授权断言；ai-service/tests/test_internal_security.py补attempt目录边界。

AI兼容：ai-service/app/security.py仅扩展严格attempt目录。其余算法和前端源码不改。报告与本机隔离工具另计。

追加必要白名单：StorageService仅增加attempt目录的逐级非链接检查与创建，避免AI调用校验前沿已有junction创建目录；快照已补入before-files.json。恶意文件系统并发替换的TOCTOU不在本次保证内。

修改前快照与hash见before-files.json及.codex-work/task-01-05-before-full。任何额外必要改动先补白名单；禁止读真实库、实例秘密配置和私人媒体，禁止全库还原、提交和推送。
