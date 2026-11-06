# 前端源码审计证据

审计日期：2026-10-22。全部 frontend/src 自有文件已通读；配置、入口、SVG、布局脚本已读；package-lock 仅核查根依赖，不逐条审计第三方。PNG未做图像质量审计。未启动浏览器/服务，下面功能状态不把构建成功当作业务验证。未发现前端伪造分析结果；默认表单值和固定标注人属于演示默认值。实际算法真假由后端/AI响应决定。

## 文件覆盖与职责

以下路径均相对项目根目录，行数包含空行。

| 文件 | 行数 | 职责 |
|---|---:|---|
| frontend/src/main.js | 8 | 挂载App、注册ElementPlus、依次加载基础和品牌样式 |
| frontend/src/App.vue | 91 | 登录切换、管理员菜单、内存section、localStorage主题/身份 |
| frontend/src/api/client.js | 18 | Axios /api、Bearer请求头、文件URL |
| frontend/src/pages/LoginPage.vue | 65 | 登录、封面视差、错误提示 |
| frontend/src/pages/WorkbenchPage.vue | 222 | 上传、任务轮询、详情选择、PDF |
| frontend/src/pages/UserPage.vue | 49 | 用户列表/创建 |
| frontend/src/pages/AnnotationPage.vue | 130 | 标注登记/导入/导出/删除 |
| frontend/src/pages/VideoAnnotationPage.vue | 394 | 视频时间标记、事件列表、annotation.v1生成 |
| frontend/src/components/EventDetail.vue | 250 | 证据/轨迹文本、复核表单 |
| frontend/src/components/ManualEventForm.vue | 50 | 任务事件手工补录 |
| frontend/src/styles.css | 403 | 基础工作台/标注/证据布局 |
| frontend/src/framecall.css | 137 | 深浅主题、品牌覆盖、移动端、减少动效 |

src共12文件1817行。另查 frontend/index.html、vite.config.js、package.json、public/brand/logo.svg；scripts/check-frontend-layout.mjs为浏览器检查辅助。Vue3、ElementPlus、Axios、Vite；实际构建Vite6.4.2。开发代理 /api → localhost:8080（vite.config.js:7-9）；生产部署代理需外部配置，未验证。无Vue Router/Pinia，页面由v-if切换，状态均为组件ref/reactive与localStorage；无前端单元测试脚本（package.json:6-10）。

## 页面—事件—API—后端映射

缩写：P=frontend/src/pages/，C=frontend/src/components/，B=backend/src/main/java/com/footballref/assist/controller/。下表后端名均位于B；接口自动加/api。所有请求默认由client.js:7-13附带Bearer；图片/video src经fileUrl直连，不经过Axios。

| 功能/位置与处理函数 | 请求参数 → 响应/消费 | 实际后端 | 状态与错误处理 |
|---|---|---|---|
| P/LoginPage:54-64 submit；App:68-72 handleLogin | POST /auth/login {username,password} → token,user{id,username,displayName,role}，保存localStorage | AuthController:28-44 login，查用户/密码/签token | 已实现但未验证；catch显示错误，finally停止loading |
| App:1-43,55-78 导航/退出 | section默认workbench；无URL路由/API；session.user控制登录，role控制菜单 | 管理接口另有ADMIN注解 | 部分实现；无服务端身份重验/导航守卫/401恢复；JSON.parse损坏缓存可抛错 |
| P/UserPage:34-47 load/create | GET /users→用户数组；POST /users {username,displayName,password,role}→新用户（忽略响应，重新加载） | UserController:20,30-46，ADMIN、查重/哈希/保存 | 已实现但未验证；无catch、无表单校验；编辑/禁用/删除未实现 |
| P/Workbench:119-139 submitUpload | POST /videos multipart(file,title,competition,teams,sensitivity)→{video,task}，读取task.id | VideoController:61-92，存文件/video、createTask/runTask | 已实现但未验证；无文件仅warning；请求只有finally，无catch |
| P/Workbench:141-157,213-221 loadTasks/selectTask/refreshSelectedTask | GET /tasks→任务数组；GET /tasks/{id}→任务+events；保存selectedTask、保留事件ID或首项 | TaskController:47-63,104-118；任务包含status/stage/progressPercent/errorMessage | 已实现但未验证；2500ms固定轮询，无catch/互斥/响应序号 |
| P/Workbench:113,163-172 cancelTask/retryTask | POST /tasks/{id}/cancel或retry→task（忽略响应再GET）；取消仅PENDING/RUNNING，重试仅FAILED按钮 | TaskController:66-75→TaskService | 已实现但未验证；无catch；成功提示不等同分析完成 |
| P/Workbench:67-83,159-161 selectEvent → C/EventDetail | event字段含类型、风险、时间、摘要、ID、keyframePath；图片GET /files?path=encoded | TaskController.detail→EventController.toEventMap:92-111；FileController:29-40 | 已实现但未验证；图片扩展名过滤，无加载失败回退 |
| C/EventDetail:123-158,183-249 metrics/trackBoxes/metricGroups | evidenceJson/trackBoxesJson字符串解析；规则/姿态/其他分组，置信度及框坐标文本 | EventController.toEventMap:103-109 | 已实现但未验证；JSON解析失败静默变[]；不绘制框叠层 |
| C/EventDetail:1-11 事件片段/逐帧/慢放 | 仅img，无video，未消费clipPath；三个按钮无@click | 后端虽然返回clipPath，前端未使用 | Mock或占位；逐帧/慢放/事件片段定位均未接通 |
| C/EventDetail:160-173 watch/submitReview | POST /events/{id}/review {result,reasonCode,note}→review{id,eventId,reviewer,result,reasonCode,note,revision,createdAt}，忽略响应后刷新任务 | EventController:49-61，reviews.save | 部分实现；提交已接通，无catch；未调用GET /events/{id}获取reviews，切事件重置表单 |
| C/ManualEventForm:40-48 create | POST /events/manual {taskId,eventType,timestampMs,summary,involvedTrackIds:number[]}→event；刷新任务 | EventController:71-90，UNCERTAIN、原视频路径作证据 | 已实现但未验证；无catch；空ID字符串Number转换可能产生0 |
| P/Workbench:174-190 downloadReport | GET /tasks/{id}/report→PDF blob；SUCCEEDED才启用；objectURL下载后释放 | TaskController:93-101→ReportService.generatePdf | 已实现但未验证；catch提示，但blob错误体无法直接读取message |
| P/AnnotationPage:55-66 load/create | GET /annotations→id/title/source/status/labelFilePath/统计等数组；POST {title,source}→task | AnnotationController:38-71,127-140，ADMIN | 已实现但未验证；创建await后提示成功；无catch |
| P/AnnotationPage:68-82 upload | POST /annotations/{id}/labels multipart(file)→更新统计任务，重新load | AnnotationController:74-89→validateLabels:162-229→写文件/保存 | 已实现但未验证；catch错误、finally清文件输入 |
| P/AnnotationPage:84-128 downloadLabels/deleteAnnotation | GET /annotations/{id}/labels或labels.csv→blob；DELETE /annotations/{id}→{deleted,id} | AnnotationController:92-124 | 已实现但未验证；删除确认、catch；导出使用objectURL |
| P/VideoAnnotation:149-230 loadVideos/selectVideo/selectLocalVideo/syncVideoMeta/markKeyframe/markWindowStart/markWindowEnd/seekRelative | GET /videos→视频数组；服务端视频GET /files?path=originalPath，本地objectURL；HTML video controls，±1s、毫秒时间标记 | VideoController:52-58,95-106；FileController:29-40 | 已实现但未验证；本地视频不上传；loadVideos无catch，时间边界校验不完整 |
| P/VideoAnnotation:233-395 addEvent/removeEvent/resetForm/buildAnnotationPayload | 内存events；annotation.v1含dataset、固定annotator、单sample、split、duration、fps=25、events及空boxes/tracks/tags | 无即时后端请求 | 部分实现；无框/轨迹标注、编辑已有事件/导入续标；切源清空events |
| P/VideoAnnotation:271-339 saveToBackend/downloadJson | 首次POST /annotations {title,source}取id；再POST /annotations/{id}/labels multipart JSON；重复保存复用savedTaskId；下载纯本地JSON | AnnotationController.create/uploadLabels | 已实现但未验证；saving防重复、catch、finally；先建任务后上传失败可留空登记 |
| App:80-90 applyTheme/toggleTheme；framecall.css:1-137 | localStorage(theme)、html.light-theme；响应式760/1050断点、reduced-motion | 不适用 | 已实现但未验证；未浏览器核验布局/主题对比度 |

除明确占位外，成功提示均位于await请求成功之后，未发现catch后伪报成功；但响应成功不代表AI成功、媒体可播或数据在重启后仍存在。生产提交/持久化未实际执行。

## 问题清单

| ID/级别 | 证据、当前行为和影响 | 毕设影响/建议/置信度/未验证 |
|---|---|---|
| FE-01 P1 | EventDetail:4-10及完整脚本：仅关键帧图，未消费clipPath，三个按钮无绑定 | 阻碍真实视频辅助复核；B保留组件并接通播放器定位/逐帧/慢放；高，静态确定，未浏览器演示 |
| FE-02 P1 | VideoAnnotation:202-225,334,341-365：固定25fps及40ms换算，窗口可越出视频末尾；默认6000ms使syncVideoMeta不会截断 | 影响帧索引、片段标注可信度；B读取实际元数据并校验范围；高，未用不同fps实测 |
| FE-03 P2 | App:10-18,40-43,55-78：管理员菜单有role条件，页面本身仅section条件；logout不清section，切裁判仍可残留管理页面 | UI权限状态不一致，后端ADMIN仍拦截，不能据此认定越权成功；B重置导航并统一守卫；高，未交互复现 |
| FE-04 P2 | client.js:3-13无response拦截；Workbench:119-172,213-219、UserPage:34-46等无catch | 网络/过期认证可无可见解释且重复轮询；B统一错误及会话失效处理；高，未故障注入 |
| FE-05 P2 | Workbench:146-151,215-219：异步setInterval无互斥；切任务无响应归属校验 | 慢请求可重叠并旧响应覆盖新选择；B序列/取消控制；高，竞态未复现 |
| FE-06 P2 | EventDetail:160-172未读取后端reviews；任务响应无reviews | 复核可保存但无法回看已有结论；B接入详情/历史，防重复提交；高，未验证DB |
| FE-07 P2 | VideoAnnotation:173-188,258-260,309-335：本地源只存文件名，中文名可能归一为manual-sample，标注人固定，框轨迹空 | 影响样本追溯和续标，不可宣称完整检测框标注工具；B使用稳定样本/真实标注人；高，未验证跨视频冲突 |
| FE-08 P2 | LoginPage:26,38；UserPage:31：默认凭据出现在页面/表单（本报告脱敏） | 演示环境便利但公开部署需移除默认凭据并轮换；B修复；高，部署暴露未验证 |
| FE-09 P2 | client.js:15-17图片/video直连；SecurityConfig:20允许files匿名；FileController:29-40仅根目录校验 | 路径已知者可请求存储文件，需与后端安全审计合并；B鉴权媒体访问；高，未发送真实越权请求 |
| FE-10 P3 | build日志：JS1083.58kB，CSS370.97kB；main.js完整引入ElementPlus；无前端单测 | 首屏体积/回归保障不足；B按需拆包并加入业务意图测试；高，网络性能未量测 |

## 测试与保留边界

scripts/check-frontend-layout.mjs:13,40-61,126-337使用已运行后端登录、注入token，检查1440/1280视口溢出及详情/表格/标注工作区存在；不验证复核持久化、权限、播放器效果或移动布局。本次未运行，因为需要服务且会覆盖storage/screenshots同名截图。

安全生产构建：先确认审计frontend-dist不存在；首轮命令npm run build -- --outDir失败，PowerShell npm包装实际丢失参数，退出1；保留evidence/frontend-build.log。等效npm.cmd重试成功，退出0，1649模块、5.46s；完整evidence/frontend-build-retry.log，新增产物evidence/frontend-dist。第三方PURE注释警告、输出目录外警告、包体积警告全部保留。状态：已实现且验证（仅构建）。本正式审计未修改业务源码或原frontend/dist；此前初步巡检的普通build曾更新dist，应在总报告范围声明中单列。

前端建议总体B保留并修复；播放链路局部补齐，无需重写全部Vue；标注框/轨迹扩展可按研究需求冻结。未核验：真实浏览器、登录/API集成、不同权限交互、不同fps媒体、PDF实际内容、网络异常、生产反代、主题视觉和移动端。未读取node_modules/dist生成代码逐文件内容；不把这类排除项冒充自有源码审阅。

