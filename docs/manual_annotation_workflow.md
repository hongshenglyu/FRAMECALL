# 视频手动标注流程

当前系统已经提供最小可用的视频标注工作台，入口在前端左侧导航的“视频标注”。

## 标注步骤

1. 启动三端服务：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-system.ps1
```

2. 使用管理员账号登录：

```text
admin / admin123
```

3. 进入“视频标注”页面。

4. 选择已上传视频，或选择本地视频临时标注。

5. 播放视频并停在争议事件附近。

6. 使用按钮记录时间：

- “关键帧”：把当前播放时间写入 `timestampMs` 和 `keyframe`。
- “窗口起点”：把当前播放时间写入 `windowStartMs`。
- “窗口终点”：把当前播放时间写入 `windowEndMs`。

7. 填写事件字段：

- 事件类型：`FOUL`、`HANDBALL`、`OFFSIDE`。
- 裁判结论：`CONFIRMED`、`REJECTED`、`UNCERTAIN`。
- 风险等级：`HIGH`、`MEDIUM`、`LOW`、`UNCERTAIN`。
- 涉事 ID：暂时手动填写，例如 `7,12`。
- 原因码：例如 `CONTACT_VISIBLE`、`INSUFFICIENT_CONTACT`、`LINE_CHECK`。
- 信心分和备注。

8. 点击“加入事件”，把当前事件加入样本。

9. 点击“保存标注”，系统会生成 `annotation.v1` JSON，创建标注任务，并上传到后端。

10. 进入“标注管理”页面，可以查看样本数、事件数、类型分布、数据划分，并导出 JSON/CSV。

## 当前边界

- 当前版本先支持事件级标注和关键帧/窗口时间标注。
- 检测框、轨迹点和姿态关键点暂时保留为空数组，后续接入画框工具和轨迹编辑。
- 使用本地视频临时标注时，JSON 中只记录原始文件名；用于训练时建议先把视频通过工作台上传到系统，再选择已上传视频进行标注。

## 标注到训练的门槛

建议先标 10-20 个片段检查流程和字段，再扩展数据量：

- 最小演示训练：每类 20-30 个事件。
- 更像样的实验：每类 80-150 个事件。
- 检测模型训练：建议至少 500-1000 帧以上的球员/足球框标注。

手动标注完成后，先运行统计脚本检查数据分布：

```powershell
node .\scripts\experiment-stats.mjs
```

有预测结果后，再运行评估脚本：

```powershell
node .\scripts\evaluate-predictions.mjs
```
