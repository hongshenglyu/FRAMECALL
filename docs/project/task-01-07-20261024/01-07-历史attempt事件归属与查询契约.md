# 版本与归档契约

GET /api/tasks/{id}、/{id}/events及/{id}/report共用TaskHistoryService.view：缺参数默认为current，非空currentAttemptId必须确实归属同task；无current的历史任务只返回attemptId=NULL的LEGACY。scope=all返回本任务全部历史；scope=legacy仅NULL；attemptId=UUID选指定轮次且需归属本task，跨task404。scope与attemptId不能组合成含糊选择，非法scope400。未知标量引用不回填，历史JSON的attemptAssociation=UNKNOWN；LEGACY不冒充真实AI来源。

手动事件绑定创建时的currentAttemptId（先核验关联），无current保持LEGACY。历史事件可按明确eventId继续复核，但归档后拒绝；新复核revision在task锁内取最大已有值+1，支持旧缺号，不重新编号或删除历史。并发操作的先后由同task锁提交确定。

DELETE现为可恢复归档：200返回deleted=true、archived=true、taskId/archivedAt，旧客户端仍可从默认列表移除任务，不代表物理删除。includeArchived=true列表可发现归档任务；POST /{id}/restore按原owner/ADMIN/assigned规则恢复，B404、匿名401，无权限不改数据/文件。归档阻断详情/事件/媒体/报告/写入，不删除独立Video原片或更改Video授权。

活动attempt（包括结果未知安全占位）及RUNNING/CANCEL_REQUESTED任务归档409。恢复也不能绕过活动占位；只改archivedAt，原状态、错误码、attempt、事件和媒体均保留。不提供永久删除或文件清理。底层Repository物理删除方法不再由业务API使用，不为旧孤立引用添加危险级联。

PDF从同一选择结果生成，文内标注选择与每条事件的attempt/LEGACY，不把上一轮结果混作新失败轮次。PDF在内存生成，避免覆盖已有文件或不同scope互相污染；原媒体边界仍检查。缺失/拒绝的预览明确标注“媒体不可用；原审计记录已保留”，GET媒体404，不静默删除审计数据。选择完成后已授权导出可继续交付，不宣称能撤回先前字节。

前端提供当前/全部/LEGACY/指定attempt选择，并传同一参数导出；归档与恢复使用相应API。Chrome实际验证归档隐藏列表、归档媒体拒绝、显示归档及恢复媒体；多版本筛选和PDF内容由Spring实际控制器+DB及PDF解析验证。
