# AI 输出 JSON 合同

本文档固定 AI 服务与 Java 后端、前端展示、PDF 报告和后续实验脚本之间的 JSON 合同。后续替换 YOLO、ByteTrack 或姿态模型时，必须保持这些字段语义稳定；新增字段可以向后兼容添加，删除或改名字段需要同步修改后端、前端、报告和实验脚本。

## 1. 通用约定

- 所有路径字段使用任务输出目录下的文件路径，后端入库前会归一化为可访问的存储路径。
- 时间统一使用毫秒，字段名为 `timestampMs`、`windowStartMs`、`windowEndMs`。
- 检测框统一为 `box: [x1, y1, x2, y2]`，坐标单位为像素。
- 置信度、分数和指标分值默认使用 `0.0` 到 `1.0` 的浮点数；有物理单位的字段必须写明 `unit`。
- `mode` 表示当前配置请求的模式，`status` 表示本次执行结果。常见状态包括 `completed`、`fallback`、`disabled`、`skipped`、`adapter_pending`。
- `mock` 和 `fallback` 结果不能被论文或实验报告当作真实模型效果，只能用于工程闭环、演示和接口稳定性验证。

## 2. analysis_result.json

位置：`{taskOutputDir}/analysis_result.json`

用途：AI 服务主返回结果，也是后端 `TaskService` 直接消费的合同。后端当前会读取 `resultJsonPath`、`events` 以及每个事件的核心字段并入库。

顶层字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `taskId` | number | 是 | 后端分析任务 ID |
| `status` | string | 是 | AI 任务最终状态，成功时为 `SUCCEEDED` |
| `stage` | string | 是 | 阶段名，完成时为 `COMPLETED` |
| `progressPercent` | number | 是 | 进度百分比，完成时为 `100` |
| `resultJsonPath` | string | 是 | 本文件路径 |
| `events` | array | 是 | 争议事件候选列表 |
| `metrics` | object | 是 | 检测、跟踪、规则和姿态模块指标 |
| `errorMessage` | string/null | 是 | 错误信息，成功时为 `null` |

可选字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `sampleId` | string | 预测结果对应的标注样本 ID。当前工程闭环不强制要求；做实验评估时建议写入，或在评估脚本中用 `--sample-id` 指定。 |

事件字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `externalId` | string | 是 | AI 侧事件 ID，例如 `T1-E01` |
| `eventType` | string | 是 | `FOUL`、`HANDBALL` 或 `OFFSIDE` |
| `riskLevel` | string | 是 | `HIGH`、`MEDIUM`、`LOW`、`UNCERTAIN` |
| `timestampMs` | number | 是 | 推荐复核关键帧时间 |
| `windowStartMs` | number | 是 | 复核片段开始时间 |
| `windowEndMs` | number | 是 | 复核片段结束时间 |
| `involvedTrackIds` | array<number> | 是 | 涉事轨迹 ID |
| `summary` | string | 是 | 候选事件摘要 |
| `evidenceMetrics` | array | 是 | 证据指标列表 |
| `trackBoxes` | array | 是 | 关键帧上的涉事目标框 |
| `keyframePath` | string | 是 | 关键帧图路径 |
| `clipPath` | string | 是 | 片段路径或占位文件路径 |
| `confidence` | number | 是 | 事件置信度 |

`evidenceMetrics` 元素字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `name` | string | 是 | 指标名，例如 `min_distance` |
| `value` | number | 是 | 指标值 |
| `unit` | string/null | 是 | 单位，例如 `px`、`score`、`px/s` |
| `description` | string | 是 | 指标说明 |

`trackBoxes` 元素字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `trackId` | number | 是 | 轨迹 ID |
| `label` | string | 是 | 目标标签，当前常用 `player` |
| `team` | string/null | 是 | 队伍标识，未知时为 `null` |
| `box` | array<number> | 是 | `[x1, y1, x2, y2]` |
| `confidence` | number | 是 | 目标框置信度 |

## 3. detections.json

位置：`{taskOutputDir}/detections/detections.json`

用途：保存 YOLO 检测输出，供真实跟踪模块和检测实验读取。默认 `mock` 模式不会生成该文件；YOLO 依赖或权重缺失时会在 `metrics.detector.note` 中说明原因并回落。

字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `mode` | string | 是 | 当前为 `yolo` |
| `model` | string | 是 | 权重路径或模型名 |
| `confidenceThreshold` | number | 是 | 检测置信度阈值 |
| `sampleStride` | number | 是 | 抽帧步长 |
| `classFilter` | array<string> | 是 | 保留类别，例如 `person`、`sports ball` |
| `frames` | array | 是 | 抽样帧检测结果 |
| `note` | string | 是 | 运行说明 |

`frames` 元素字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `sampleIndex` | number | 是 | 抽样序号 |
| `frameIndex` | number | 是 | 原视频帧号 |
| `detections` | array | 是 | 当前帧检测框 |

`detections` 元素字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `label` | string | 是 | 类别名 |
| `classId` | number | 是 | 模型类别 ID |
| `confidence` | number | 是 | 检测置信度 |
| `box` | array<number> | 是 | `[x1, y1, x2, y2]` |

## 4. tracks.json

位置：`{taskOutputDir}/tracks/tracks.json`

用途：保存多目标跟踪输出。当前 `mock` 模式生成稳定轨迹；`bytetrack` 模式在真实 ByteTrack 接入前会先把 YOLO 检测结果归一化到同一合同。

字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `mode` | string | 是 | `mock`、`bytetrack` 或未知回落模式 |
| `status` | string | 是 | `completed`、`fallback` 或 `adapter_pending` |
| `tracker` | string | 是 | 跟踪器名 |
| `trackCount` | number | 是 | 轨迹数量 |
| `observationCount` | number | 是 | 轨迹观测总数 |
| `tracks` | array | 是 | 轨迹列表 |
| `note` | string | 是 | 运行说明 |

`tracks` 元素字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `trackId` | number | 是 | 稳定轨迹 ID |
| `label` | string | 是 | 目标标签 |
| `team` | string/null | 是 | 队伍标识，未知时为 `null` |
| `observations` | array | 是 | 该轨迹的时间序列观测 |

`observations` 元素字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `timestampMs` | number | 是 | 观测时间 |
| `frameIndex` | number | 是 | 原视频帧号或采样帧号 |
| `box` | array<number> | 是 | `[x1, y1, x2, y2]` |
| `confidence` | number | 是 | 跟踪框置信度 |

## 5. foul_candidates.json

位置：`{taskOutputDir}/rules/foul_candidates.json`

用途：保存接触类犯规候选规则输出。当前 `track-rule-v1` 使用最小距离、相对速度突变、闭合速度进行加权打分。

字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `mode` | string | 是 | `track-rule-v1`、`mock` 或未知回落模式 |
| `status` | string | 是 | `completed` 或 `fallback` |
| `scorer` | string | 是 | 规则打分器名 |
| `candidateCount` | number | 是 | 候选数量 |
| `selected` | object/null | 是 | 被选中的 FOUL 候选 |
| `candidates` | array | 是 | 全部候选列表 |
| `thresholds` | object | 是 | 本次规则阈值 |
| `note` | string | 是 | 运行说明 |

候选字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `trackIds` | array<number> | 是 | 候选球员轨迹对 |
| `timestampMs` | number | 是 | 候选关键帧时间 |
| `windowStartMs` | number | 是 | 候选片段开始 |
| `windowEndMs` | number | 是 | 候选片段结束 |
| `score` | number | 是 | 规则分数 |
| `confidence` | number | 是 | 候选置信度 |
| `minDistancePx` | number | 是 | 最小中心距离 |
| `relativeVelocityDelta` | number | 是 | 相对速度突变量 |
| `closingSpeed` | number | 是 | 接近速度 |
| `contactWindowScore` | number | 是 | 接触窗口分数 |
| `summary` | string | 是 | 候选摘要 |
| `evidenceMetrics` | array | 是 | 证据指标，结构同 `analysis_result.json` |

`thresholds` 字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `distancePx` | number | 是 | 距离阈值 |
| `velocityDeltaPxS` | number | 是 | 相对速度突变阈值 |
| `closingSpeedPxS` | number | 是 | 接近速度阈值 |

## 6. pose_features.json

位置：`{taskOutputDir}/pose/pose_features.json`

用途：只对被选中的 FOUL 候选球员对保存局部姿态特征。当前 `local-rule-v1` 是局部裁剪框代理特征，不代表真实姿态模型效果。

字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `mode` | string | 是 | `local-rule-v1`、`mock` 或未知回落模式 |
| `status` | string | 是 | `completed`、`fallback`、`disabled` 或 `skipped` |
| `estimator` | string | 是 | 姿态估计器名 |
| `scope` | string | 是 | 当前固定为 `selected_foul_candidate_pair` |
| `selectedTrackIds` | array<number> | 是 | 被分析的轨迹 ID |
| `timestampMs` | number/null | 是 | 局部姿态分析时间 |
| `poseScore` | number | 是 | 姿态异常分数 |
| `cropBox` | array<number>/null | 是 | 局部裁剪区域 |
| `keypoints` | object | 是 | 轨迹 ID 到关键点集合的映射 |
| `evidenceMetrics` | array | 是 | 姿态证据指标 |
| `note` | string | 是 | 运行说明 |

`keypoints` 以字符串形式的 `trackId` 为 key，每个关键点为 `[x, y]` 坐标。当前代理版本常见关键点包括 `head`、`leftShoulder`、`rightShoulder`、`leftHip`、`rightHip`、`leftKnee`、`rightKnee`。

## 7. metrics 字段

`analysis_result.json.metrics` 汇总各模块运行状态，供前端证据面板、PDF 报告、实验统计和故障排查使用。

- `metrics.detector`：检测模式、状态、模型、抽样帧数、检测数量、`detectionsJsonPath`、预览图、`mAP50`、`precision`、`recall`、`fps`、说明。
- `metrics.tracking`：跟踪模式、状态、轨迹器、轨迹数量、观测数量、`tracksJsonPath`、预览图、`idf1`、`idSwitches`、`fps`、说明。
- `metrics.foulCandidate`：规则模式、状态、打分器、候选数量、`rulesJsonPath`、选中轨迹、选中时间、规则分数、距离和速度特征、`recall`、每 3 分钟误报数、关键帧误差、说明。
- `metrics.poseEstimation`：姿态模式、状态、估计器、分析范围、选中轨迹、时间、姿态分数、`featuresJsonPath`、预览图、说明。

在没有标注数据前，`mAP50`、`precision`、`recall`、`idf1`、`falsePositivesPer3Min` 和 `keyframeErrorFrames` 等实验指标可以为 `0.0`，但字段必须保留，便于后续真实实验无缝接入。

## 8. 替换真实模型时的最低要求

- 真实 YOLO 接入后，必须继续写 `detections.json`，并让 `metrics.detector.status` 表示真实执行状态。
- 真实 ByteTrack 接入后，必须继续写 `tracks.json`，并保证同一片段内 `trackId` 稳定。
- 犯规候选规则或模型升级后，必须继续写 `foul_candidates.json`，并保留可解释证据指标。
- 姿态模型接入后，必须继续只处理候选球员对，输出 `pose_features.json`，不能默认全视频跑姿态。
- 任一真实模型失败时，必须通过 `status=fallback` 和 `note` 说明原因，并保持 `analysis_result.json` 能让后端闭环成功。
