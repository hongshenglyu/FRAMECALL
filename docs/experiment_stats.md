# 实验统计脚本

`scripts/experiment-stats.mjs` 用于把 `annotation.v1` 标注数据整理成论文实验可引用的统计报告。脚本支持两种输入：

- `annotation.v1` JSON：保留样本、事件、检测框和轨迹字段，统计最完整。
- 事件级 CSV：可直接使用标注管理页导出的 CSV，但只包含事件级字段，无法统计检测框和轨迹观测。

## 使用命令

使用示例标注文件：

```powershell
node .\scripts\experiment-stats.mjs --input .\docs\examples\annotation_labels.experiment_sample.json
```

使用标注管理页导出的 CSV：

```powershell
node .\scripts\experiment-stats.mjs --input .\storage\exports\annotation-labels.csv
```

不传 `--input` 时，脚本会从 `storage/annotations` 中自动选择最新的 `annotation.v1` JSON：

```powershell
node .\scripts\experiment-stats.mjs
```

指定输出目录：

```powershell
node .\scripts\experiment-stats.mjs --input .\docs\examples\annotation_labels.experiment_sample.json --out .\storage\experiments
```

## 输出文件

默认输出到 `storage/experiments`：

- `*.stats.json`：结构化统计结果，供后续评估脚本继续读取。
- `*.stats.md`：Markdown 摘要，适合直接放入论文实验记录或答辩材料草稿。

## 统计内容

总览：

- 样本数、事件数、总时长。
- 检测框数、轨迹数、轨迹观测数。

按 `split` 汇总：

- `train`、`val`、`test`、`demo` 的样本数、事件数和时长。
- `FOUL`、`HANDBALL`、`OFFSIDE` 事件数量。
- `CONFIRMED`、`REJECTED`、`UNCERTAIN` 复核结果数量。
- 平均标注信心分。

检测与跟踪：

- 检测框总数、有检测框的事件数、检测框平均置信度。
- 轨迹总数、唯一轨迹 ID 数、轨迹观测总数、每条轨迹平均观测数。
- 轨迹可见性分布，例如 `visible`、`partial`、`occluded`、`lost`。

## 当前边界

本脚本统计的是标注数据自身的分布和质量，不计算模型预测与真值之间的正式指标。下一阶段需要新增预测对齐脚本，把 AI 输出与 `annotation.v1` 真值按样本、事件类型、时间窗口和关键帧进行匹配，再计算召回率、误报数、关键帧误差等实验指标。
