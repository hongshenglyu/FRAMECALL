# 预测结果评估脚本

`scripts/evaluate-predictions.mjs` 用于把 AI 预测结果与 `annotation.v1` 标注真值对齐，输出论文实验中更接近正式指标的评估报告。

## 输入要求

真值输入：

- `annotation.v1` JSON。
- 默认排除 `label=REJECTED` 的事件，只把 `CONFIRMED` 和 `UNCERTAIN` 当作正样本。
- 如果需要把 `REJECTED` 也纳入匹配，可加 `--include-rejected`。

预测输入：

- 单个 `analysis_result.json` 文件。
- 或包含多份 `analysis_result.json` 的目录，脚本会递归扫描。
- 如果预测文件没有 `sampleId`，单文件评估时应使用 `--sample-id` 指定对应样本；多文件评估建议把预测文件放在以样本 ID 命名的目录中，或在预测 JSON 顶层/事件上写入 `sampleId`。

## 使用命令

使用示例真值和示例预测目录：

```powershell
node .\scripts\evaluate-predictions.mjs --truth .\docs\examples\annotation_labels.experiment_sample.json --prediction .\docs\examples\predictions
```

评估单个 AI 输出文件，并指定它对应的样本：

```powershell
node .\scripts\evaluate-predictions.mjs --truth .\docs\examples\annotation_labels.experiment_sample.json --prediction .\storage\tasks\task-16\analysis_result.json --sample-id clip-train-0001
```

调整匹配容差和置信度阈值：

```powershell
node .\scripts\evaluate-predictions.mjs --truth .\docs\examples\annotation_labels.experiment_sample.json --prediction .\docs\examples\predictions --tolerance-ms 3000 --confidence-threshold 0.7
```

不传参数时，脚本会尝试使用最新上传的 `annotation.v1` 和最新生成的 `analysis_result.json`：

```powershell
node .\scripts\evaluate-predictions.mjs
```

## 匹配规则

脚本按以下顺序匹配：

1. `sampleId` 必须一致。
2. `eventType` 必须一致。
3. 预测时间与真值关键帧时间误差不超过 `--tolerance-ms`，默认 3000 ms。
4. 如果时间误差超过容差，但预测窗口和真值窗口有重叠，也可作为窗口重叠匹配。
5. 一个预测事件只能匹配一个真值事件，脚本用贪心策略选择时间误差最小的候选。

## 输出指标

默认输出到 `storage/experiments`：

- `*-prediction-eval-*.json`：结构化评估结果。
- `*-prediction-eval-*.md`：Markdown 评估报告。

总览指标：

- 真值事件数、预测事件数。
- `TP`、`FP`、`FN`。
- `Precision`、`Recall`、`F1`。
- 每 3 分钟误报数。
- 平均关键帧误差，单位包括毫秒和帧。

分组指标：

- 按 `split` 汇总：`train`、`val`、`test`、`demo`。
- 按事件类型汇总：`FOUL`、`HANDBALL`、`OFFSIDE`。
- 匹配明细：真值事件 ID、预测事件 ID、时间误差、预测置信度和匹配原因。

## 当前边界

当前评估只做事件级匹配，不计算检测框 IoU、MOT 指标或姿态关键点误差。检测、跟踪和姿态的正式指标应在真实 YOLO、ByteTrack 和姿态模型稳定输出后继续扩展。
