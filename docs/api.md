# API 摘要

前端只调用 Java 后端，不直接调用 Python AI 服务。

AI 服务输出合同见：[AI 输出 JSON 合同](ai_output_contract.md)。

## 认证

- `POST /api/auth/login`

## 视频与任务

- `GET /api/videos`
- `POST /api/videos`
- `GET /api/tasks`
- `GET /api/tasks/{taskId}`
- `POST /api/tasks/{taskId}/cancel`
- `POST /api/tasks/{taskId}/retry`
- `DELETE /api/tasks/{taskId}`
- `GET /api/tasks/{taskId}/report`

## 事件复核

- `GET /api/tasks/{taskId}/events`
- `GET /api/events/{eventId}`
- `POST /api/events/{eventId}/review`
- `PATCH /api/events/{eventId}/tracks`
- `POST /api/events/manual`

## 标注管理

- `GET /api/annotations`
- `POST /api/annotations`
- `POST /api/annotations/{annotationId}/labels`
- `GET /api/annotations/{annotationId}/labels`
- `GET /api/annotations/{annotationId}/labels.csv`

标注文件必须使用 `annotation.v1` JSON。上传成功后，接口返回并保存 `sampleCount`、`eventCount`、`eventTypeSummary` 和 `splitSummary`，其中 `split` 当前支持 `train`、`val`、`test`、`demo`。
JSON 导出会返回原始标注文件；CSV 导出会按事件级字段展开，便于后续论文实验统计。
