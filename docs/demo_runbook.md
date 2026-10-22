# 演示运行手册

本手册用于答辩或日常回归演示。默认模式先验证系统闭环，不强制依赖真实 YOLO 模型。

## 1. 一键启动

在项目根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-system.ps1
```

脚本会按顺序检查并启动：

- AI 服务：`http://localhost:8001`
- Java 后端：`http://localhost:8080`
- Vue 前端：`http://localhost:5173`

如果端口上已经有对应服务，脚本会复用现有进程，不重复启动。

默认情况下，启动完成后会自动跑一次闭环 smoke 验证。只想启动三端时使用：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-system.ps1 -SkipSmoke
```

## 2. 登录账号

- 管理员：`admin / admin123`
- 裁判：`referee / referee123`

## 3. 单独跑闭环验证

三端已启动后，执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\smoke-system.ps1
```

验证内容：

- 登录成功
- 上传短视频成功
- 后端创建异步任务
- AI 返回 `FOUL`、`HANDBALL`、`OFFSIDE` 三类事件，其中 `FOUL` 使用轨迹规则候选
- 事件详情接口可打开
- 裁判复核记录可保存
- 手动补录争议事件成功
- PDF 报告可导出

看到 `SMOKE PASS` 即代表当前闭环可演示。

前端页面里的“导出PDF”按钮会通过登录态下载报告文件，文件名格式为：

```text
referee-review-report-task-{taskId}.pdf
```

当前报告内容包含：

- 任务概览：视频标题、赛事、对阵双方、任务状态、事件数量、已复核数量
- 争议事件列表：事件 ID、类型、风险、时间、置信度、涉事 ID、复核结论
- 事件复核详情：事件摘要、关键帧缩略图、轨迹规则证据、局部姿态证据、轨迹框、裁判复核结果、备注、关键帧和片段路径

报告使用系统中文字体生成，可在 Windows/Edge/Acrobat 中直接查看中文标题、表格和详情正文。

## 4. 前端布局检查

三端启动后，可以执行：

```powershell
node .\scripts\check-frontend-layout.mjs
```

脚本会使用本机 Chrome/Edge 登录工作台，在 1440px 和 1280px 宽度下选择一个任务并检查横向溢出，截图保存到 `storage\screenshots`。

## 5. 可选 YOLO 检测试跑

默认启动脚本使用 mock analyzer。需要验证 YOLO 检测入口时，先准备可选依赖和本地 `.pt` 权重：

```powershell
cd ai-service
.\.venv\Scripts\python -m pip install -r requirements-yolo.txt
$env:AI_ANALYZER_MODE = "yolo"
$env:AI_YOLO_MODEL = "models\yolo\yolov8n.pt"
$env:AI_TRACKER_MODE = "bytetrack"
$env:AI_POSE_MODE = "local-rule-v1"
.\.venv\Scripts\python -m uvicorn app.main:app --reload --port 8001
```

YOLO 成功运行后，任务输出目录会多出：

- `detections\detections.json`
- `detections\detections_preview.jpg`
- `tracks\tracks.json`
- `tracks\tracks_preview.png`
- `rules\foul_candidates.json`
- `pose\pose_features.json`
- `pose\pose_preview.png`

当前 YOLO 只负责抽帧检测 `person` 和 `sports ball` 等目标；`AI_TRACKER_MODE=bytetrack` 会先把检测结果落到统一轨迹合同里，真实 ByteTrack 关联仍是下一步替换点。`FOUL` 候选使用 `track-rule-v1` 计算最小距离、相对速度突变和闭合速度，并只对选中的球员对做局部姿态特征；手球和越位仍是候选提示。缺少依赖或权重时，系统会回落并继续产出可演示的三类事件。

## 6. 清理演示数据

先停止后端，再执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\reset-demo.ps1
```

清理范围只包含：

- `storage/uploads`
- `storage/tasks`
- `storage/annotations`
- `storage/dev-db`

如果后端仍在运行，脚本会拦截，避免 H2 文件占用时误删。

## 7. 停止三端

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\stop-system.ps1
```

脚本会优先停止由 `run-system.ps1` 启动的进程，并检查 5173、8080、8001 三个端口上的本项目进程。

## 8. 常见情况

如果后端在中文路径下启动失败，请使用本项目脚本启动。脚本会自动通过 `Z:` 临时映射路径，并使用 `tools\m2-repository` 作为 Maven 本地仓库。

如果只想验证后端编译：

```powershell
cmd /c subst Z: "%CD%"
$env:JAVA_HOME = "Z:\tools\jdk-17.0.19+10"
$env:Path = "$env:JAVA_HOME\bin;Z:\tools\apache-maven-3.9.9\bin;$env:Path"
cd Z:\backend
mvn "-Dmaven.repo.local=Z:\tools\m2-repository" -DskipTests compile
```
