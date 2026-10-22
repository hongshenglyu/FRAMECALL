# 实验优先路线

完整年度节奏见：[一年期项目路线图](year_roadmap.md)。本文只保留实验阶段的概要优先级。

## 第一阶段：接口与闭环

- 使用 `ai-service` mock analyzer 跑通上传、异步任务、事件列表、事件详情、裁判复核、PDF 报告。
- 固化 AI 输出 JSON 合同，见：[AI 输出 JSON 合同](ai_output_contract.md)。
- 验收标准：一个 1-3 分钟视频可以生成 FOUL、HANDBALL、OFFSIDE 三类候选事件。

## 第二阶段：检测与跟踪

- 已加入可选 YOLO 检测入口；默认 mock，缺少依赖或权重时自动回落。
- 已加入轨迹输出合同；默认生成 mock track id、`tracks.json` 和轨迹预览图。
- 标注数据格式草案见：[数据标注格式草案](annotation_data_format.md)。
- 视频手动标注流程见：[视频手动标注流程](manual_annotation_workflow.md)，当前可记录事件关键帧、复核窗口、事件类型、裁判结论和原因码。
- 标注实验统计脚本见：[实验统计脚本](experiment_stats.md)，当前可按 `split` 汇总样本、事件、检测框、轨迹和复核结果。
- 预测结果评估脚本见：[预测结果评估脚本](prediction_evaluation.md)，当前可计算事件级 Precision、Recall、F1、每 3 分钟误报数和关键帧误差。
- 将 YOLO 检测结果稳定接入后，再把 mock detector 替换为 YOLO 小/中模型。
- 将 mock/adapter track id 替换为真实 ByteTrack 输出。
- 最低指标：`mAP + Precision + Recall + FPS`，`IDF1 + ID Switch + 轨迹可视化`。

## 第三阶段：接触类犯规候选

- 已加入 `track-rule-v1`：用球员间最小距离、相对速度突变、闭合速度做规则加权打分。
- 已加入 `local-rule-v1`：只对选中的犯规候选球员对生成局部姿态异常特征和预览图。
- 再用裁判标注的片段级 + 关键帧数据做模型校正。
- 关键帧定义：首次明显身体接触帧，允许 ±3 帧容差。
- 最低指标：`Recall + 每3分钟误报数 + 关键帧定位误差`。

## 第四阶段：业务 A/B 评测

- 邀请 5-10 名裁判/裁判学员。
- 使用 20-30 个争议片段。
- 采用交叉分组，记录复核耗时、与专家裁判真值一致率、主观信心分。
