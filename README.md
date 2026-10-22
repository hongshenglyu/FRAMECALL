# 足球裁判辅助争议事件分析系统

这是一个面向完整毕业设计的足球裁判辅助复核系统。当前版本已经搭好三段式结构：

- `backend/`: Spring Boot 业务后端，负责登录、用户、视频、异步任务、事件复核、PDF 报告。
- `ai-service/`: Python FastAPI AI 服务，默认提供可替换 mock analyzer，并预留可选 YOLO 检测、轨迹输出、犯规规则候选和局部姿态特征入口，产出与未来 YOLO + ByteTrack + 姿态模型一致的 JSON 合同。
- `frontend/`: Vue3 + Element Plus 裁判快速复核工作台。

## 当前优先级

1. 固化当前三端闭环和 AI 输出合同。
2. 建设数据与标注体系。
3. 逐步替换真实检测、跟踪、姿态、犯规候选模块。
4. 形成可复现实验评估后，再系统写论文。

长期路线见：[一年期项目路线图](docs/year_roadmap.md)。

## 已落地能力

- 三端闭环：视频上传、AI 分析、争议事件复核、人工补录、PDF 报告导出。
- 合同校验：AI 输出合同有单元测试，后端接口有 smoke 级集成测试。
- 标注体系：支持上传 `annotation.v1` JSON，后端校验基础结构并统计样本数、事件数、事件类型分布和数据划分分布，可导出原始 JSON 与事件级 CSV。
- 手动标注：提供视频标注工作台，可记录关键帧、时间窗口、事件类型、裁判结论和原因码，并保存为 `annotation.v1`。
- 实验统计：提供按 `split` 汇总标注数据的统计脚本，输出 JSON 与 Markdown 报告。
- 预测评估：提供预测结果与标注真值对齐脚本，计算 Precision、Recall、F1、每 3 分钟误报数和关键帧误差。
- 前端回归：提供 1440px / 1280px 布局检查脚本，避免复核工作台出现横向溢出。

## 默认账号

- 管理员：`admin / admin123`
- 裁判：`referee / referee123`

## 快速启动

推荐直接使用项目脚本：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-system.ps1
```

也可以手动启动三端。先启动 AI 服务：

```powershell
cd ai-service
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
.\.venv\Scripts\python -m uvicorn app.main:app --reload --port 8001
```

再启动 Java 后端：

```powershell
cd backend
mvn spring-boot:run
```

再启动前端：

```powershell
cd frontend
cmd /c npm install
cmd /c npm run dev
```

访问：

```text
http://localhost:5173
```

手动标注流程见：[视频手动标注流程](docs/manual_annotation_workflow.md)。

当前开发默认用 H2 文件库，便于先跑通代码。最终演示可使用：

```powershell
mvn spring-boot:run -Dspring-boot.run.profiles=mysql
```

并连接本机 MySQL 8。

## 基线验证

三端启动后，跑完整闭环 smoke：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\smoke-system.ps1
```

单独验证 AI 输出合同：

```powershell
.\ai-service\.venv\Scripts\python.exe -m unittest discover -s ai-service\tests
```

单独验证后端接口：

```powershell
cmd /c subst Z: "%CD%"
$env:JAVA_HOME = "Z:\tools\jdk-17.0.19+10"
$env:Path = "$env:JAVA_HOME\bin;Z:\tools\apache-maven-3.9.9\bin;$env:Path"
cd Z:\backend
mvn "-Dmaven.repo.local=Z:\tools\m2-repository" test
```

三端启动后检查前端 1440px / 1280px 布局：

```powershell
node .\scripts\check-frontend-layout.mjs
```

生成标注实验统计报告：

```powershell
node .\scripts\experiment-stats.mjs --input .\docs\examples\annotation_labels.experiment_sample.json
```

脚本说明见：[实验统计脚本](docs/experiment_stats.md)。

评估预测结果与标注真值：

```powershell
node .\scripts\evaluate-predictions.mjs --truth .\docs\examples\annotation_labels.experiment_sample.json --prediction .\docs\examples\predictions
```

脚本说明见：[预测结果评估脚本](docs/prediction_evaluation.md)。

## 可选 YOLO 检测入口

默认仍使用 mock analyzer，保证答辩演示闭环稳定。需要试跑 YOLO 时，先安装可选依赖和本地权重，再启动 AI 服务：

```powershell
cd ai-service
.\.venv\Scripts\python -m pip install -r requirements-yolo.txt
$env:AI_ANALYZER_MODE = "yolo"
$env:AI_YOLO_MODEL = "models\yolo\yolov8n.pt"
$env:AI_TRACKER_MODE = "bytetrack"
$env:AI_POSE_MODE = "local-rule-v1"
.\.venv\Scripts\python -m uvicorn app.main:app --reload --port 8001
```

如果依赖或权重不存在，AI 服务会自动回落，任务仍然产出三类候选事件，并在 `metrics.detector.note` / `metrics.tracking.note` 中说明原因。每个任务会输出 `tracks\tracks.json`，供后续 ByteTrack 接入和轨迹可视化复用；`FOUL` 候选会额外输出 `rules\foul_candidates.json` 和 `pose\pose_features.json`，记录距离、相对速度突变、闭合速度和局部姿态异常特征。
