# 数据标注格式草案

> 历史兼容格式说明。P5真实接触试标使用`framecall.contact-annotations.v1`及实际源PTS，规范与操作见[接触试标规范](manual_annotation_workflow.md)。以下annotation.v1的旧类型、固定FPS示例及训练说明不作为本轮接触标注或真值依据。

本文档定义下一阶段数据与标注体系的最小 JSON 格式。当前后端 `AnnotationTask` 已支持登记标注任务并上传 `annotation.v1` 标注文件；上传时会校验基础结构，统计样本数量、事件数量、事件类型分布和数据划分分布，并在标注管理页展示。视频手动标注流程见：[视频手动标注流程](manual_annotation_workflow.md)。后续页面编辑、AI 服务读取和实验脚本统计都围绕同一合同扩展。

## 1. 设计目标

- 支持片段级事件标注：`FOUL`、`HANDBALL`、`OFFSIDE`。
- 支持关键帧标注：首次明显身体接触帧允许 `±3` 帧容差。
- 支持涉事轨迹、检测框和裁判复核标签。
- 每个样本记录来源、时长、标注人、标注版本和数据划分。
- 可以无损导出 JSON，也能按事件级字段导出 CSV 做论文统计。

## 2. 文件命名

推荐文件名：

```text
annotation_labels.v1.json
```

如果同一批数据多次修订，使用：

```text
annotation_labels.v1.2026-06-20.json
```

## 3. 顶层结构

```json
{
  "schemaVersion": "annotation.v1",
  "datasetId": "campus-football-small-v1",
  "datasetName": "校园足球争议片段小样本",
  "createdAt": "2026-06-20T10:00:00+08:00",
  "updatedAt": "2026-06-20T10:00:00+08:00",
  "annotators": [
    {
      "annotatorId": "referee-001",
      "displayName": "裁判员A",
      "role": "REFEREE"
    }
  ],
  "samples": []
}
```

字段说明：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `schemaVersion` | string | 是 | 当前固定为 `annotation.v1` |
| `datasetId` | string | 是 | 数据集稳定 ID |
| `datasetName` | string | 是 | 数据集名称 |
| `createdAt` | string | 是 | ISO 8601 时间 |
| `updatedAt` | string | 是 | ISO 8601 时间 |
| `annotators` | array | 是 | 标注人列表 |
| `samples` | array | 是 | 视频样本或片段样本列表 |

## 4. sample 结构

```json
{
  "sampleId": "clip-0001",
  "source": {
    "type": "local_file",
    "uri": "storage/uploads/demo-clip-0001.mp4",
    "originalName": "demo-clip-0001.mp4"
  },
  "split": "train",
  "competition": "校园足球赛",
  "teams": "蓝队 vs 红队",
  "durationMs": 75000,
  "fps": 25.0,
  "frameCount": 1875,
  "width": 1280,
  "height": 720,
  "events": []
}
```

字段说明：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `sampleId` | string | 是 | 样本稳定 ID |
| `source` | object | 是 | 视频来源信息 |
| `split` | string | 是 | `train`、`val`、`test` 或 `demo` |
| `competition` | string | 否 | 赛事信息 |
| `teams` | string | 否 | 对阵双方 |
| `durationMs` | number | 是 | 视频或片段时长 |
| `fps` | number | 是 | 帧率 |
| `frameCount` | number | 否 | 总帧数 |
| `width` | number | 否 | 视频宽度 |
| `height` | number | 否 | 视频高度 |
| `events` | array | 是 | 争议事件标注 |

`source.type` 可选：

- `local_file`：本地文件。
- `public_url`：公开 URL。
- `dataset_ref`：外部数据集引用。

## 5. event 结构

```json
{
  "eventId": "clip-0001-e01",
  "eventType": "FOUL",
  "label": "CONFIRMED",
  "riskLevel": "HIGH",
  "timestampMs": 12400,
  "keyframe": {
    "frameIndex": 310,
    "timestampMs": 12400,
    "toleranceFrames": 3,
    "definition": "首次明显身体接触帧"
  },
  "windowStartMs": 9400,
  "windowEndMs": 15400,
  "involvedTrackIds": [7, 12],
  "decision": {
    "onFieldDecision": "NO_CALL",
    "expertDecision": "FOUL",
    "reviewResult": "CONFIRMED",
    "confidence": 0.9,
    "reasonCode": "CONTACT_VISIBLE",
    "note": "蓝队7号与红队12号发生明显身体接触。"
  },
  "boxes": [],
  "tracks": [],
  "tags": ["contact", "tackle"],
  "createdBy": "referee-001",
  "createdAt": "2026-06-20T10:05:00+08:00"
}
```

字段说明：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `eventId` | string | 是 | 事件稳定 ID |
| `eventType` | string | 是 | `FOUL`、`HANDBALL`、`OFFSIDE` |
| `label` | string | 是 | `CONFIRMED`、`REJECTED`、`UNCERTAIN` |
| `riskLevel` | string | 否 | `HIGH`、`MEDIUM`、`LOW`、`UNCERTAIN` |
| `timestampMs` | number | 是 | 事件中心时间 |
| `keyframe` | object | 是 | 关键帧标注 |
| `windowStartMs` | number | 是 | 片段开始时间 |
| `windowEndMs` | number | 是 | 片段结束时间 |
| `involvedTrackIds` | array<number> | 否 | 涉事轨迹 ID |
| `decision` | object | 是 | 裁判或专家决策 |
| `boxes` | array | 否 | 关键帧检测框标注 |
| `tracks` | array | 否 | 轨迹标注 |
| `tags` | array<string> | 否 | 标签，例如 `contact`、`tackle` |
| `createdBy` | string | 是 | 标注人 ID |
| `createdAt` | string | 是 | 标注时间 |

## 6. boxes 结构

```json
{
  "frameIndex": 310,
  "timestampMs": 12400,
  "trackId": 7,
  "label": "player",
  "team": "A",
  "box": [159.0, 121.6, 239.0, 301.6],
  "confidence": 1.0,
  "annotatedBy": "referee-001"
}
```

字段保持与 AI 输出合同中的 `trackBoxes` 一致，便于检测、跟踪和事件结果对齐。

## 7. tracks 结构

```json
{
  "trackId": 7,
  "label": "player",
  "team": "A",
  "observations": [
    {
      "frameIndex": 235,
      "timestampMs": 9400,
      "box": [113.0, 120.0, 193.0, 300.0],
      "visibility": "visible"
    }
  ]
}
```

`visibility` 可选值：

- `visible`：清晰可见。
- `partial`：部分遮挡。
- `occluded`：明显遮挡。
- `lost`：目标丢失。

## 8. 事件类型补充要求

`FOUL` 至少标注：

- `keyframe`：首次明显身体接触帧。
- `involvedTrackIds`：双方球员 ID。
- `decision.reasonCode`：例如 `CONTACT_VISIBLE`、`INSUFFICIENT_CONTACT`、`INSUFFICIENT_EVIDENCE`。

`HANDBALL` 至少标注：

- 球接近或接触上肢的候选帧。
- 涉事球员 ID。
- 是否存在可见上肢区域证据。

`OFFSIDE` 至少标注：

- 传球或触球瞬间关键帧。
- 进攻方候选球员 ID。
- 防守线或倒数第二名防守队员的说明。
- 场地标定可信度说明。

## 9. CSV 导出字段

论文统计时，事件级 CSV 至少包含：

| 字段 | 说明 |
| --- | --- |
| `datasetId` | 数据集 ID |
| `sampleId` | 样本 ID |
| `split` | 数据划分 |
| `eventId` | 事件 ID |
| `eventType` | 事件类型 |
| `label` | 标注结论 |
| `timestampMs` | 事件时间 |
| `keyframeFrameIndex` | 关键帧帧号 |
| `windowStartMs` | 片段开始 |
| `windowEndMs` | 片段结束 |
| `involvedTrackIds` | 涉事 ID，使用 `;` 拼接 |
| `expertDecision` | 专家结论 |
| `reasonCode` | 原因代码 |
| `confidence` | 标注信心 |
| `createdBy` | 标注人 |

## 10. 最小完整示例

```json
{
  "schemaVersion": "annotation.v1",
  "datasetId": "campus-football-small-v1",
  "datasetName": "校园足球争议片段小样本",
  "createdAt": "2026-06-20T10:00:00+08:00",
  "updatedAt": "2026-06-20T10:00:00+08:00",
  "annotators": [
    {
      "annotatorId": "referee-001",
      "displayName": "裁判员A",
      "role": "REFEREE"
    }
  ],
  "samples": [
    {
      "sampleId": "clip-0001",
      "source": {
        "type": "local_file",
        "uri": "storage/uploads/demo-clip-0001.mp4",
        "originalName": "demo-clip-0001.mp4"
      },
      "split": "demo",
      "competition": "校园足球赛",
      "teams": "蓝队 vs 红队",
      "durationMs": 75000,
      "fps": 25.0,
      "frameCount": 1875,
      "width": 1280,
      "height": 720,
      "events": [
        {
          "eventId": "clip-0001-e01",
          "eventType": "FOUL",
          "label": "CONFIRMED",
          "riskLevel": "HIGH",
          "timestampMs": 12400,
          "keyframe": {
            "frameIndex": 310,
            "timestampMs": 12400,
            "toleranceFrames": 3,
            "definition": "首次明显身体接触帧"
          },
          "windowStartMs": 9400,
          "windowEndMs": 15400,
          "involvedTrackIds": [7, 12],
          "decision": {
            "onFieldDecision": "NO_CALL",
            "expertDecision": "FOUL",
            "reviewResult": "CONFIRMED",
            "confidence": 0.9,
            "reasonCode": "CONTACT_VISIBLE",
            "note": "蓝队7号与红队12号发生明显身体接触。"
          },
          "boxes": [],
          "tracks": [],
          "tags": ["contact"],
          "createdBy": "referee-001",
          "createdAt": "2026-06-20T10:05:00+08:00"
        }
      ]
    }
  ]
}
```

## 11. 实现状态与后续顺序

1. 已完成：后端上传标注文件时校验 `schemaVersion`、`samples`、`sampleId`、`events` 和三类 `eventType` 基本结构；不符合 `annotation.v1` 的文件返回 400。
2. 已完成：标注管理页展示样本数量、事件数量、事件类型分布和数据划分统计，例如 `train`、`val`、`test`、`demo` 的样本数量。
3. 已完成：增加 JSON/CSV 导出接口，CSV 按事件级字段展开。
4. 已完成：视频标注工作台支持记录关键帧、时间窗口、事件类型、裁判结论和原因码，并保存为 `annotation.v1`。
5. 已完成：实验统计脚本按 `split` 读取 JSON/CSV，汇总样本、事件、检测框、轨迹和复核结果，见 [实验统计脚本](experiment_stats.md)。
6. 已完成：预测结果与标注真值对齐，计算事件级 Precision、Recall、F1、每 3 分钟误报数和关键帧定位误差，见 [预测结果评估脚本](prediction_evaluation.md)。
7. 待实现：接入真实模型后继续扩展检测框 IoU、MOT 跟踪指标和姿态关键点误差。
