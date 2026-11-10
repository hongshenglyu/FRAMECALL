# P2 首次真实ByteTrack连续跟踪｜2026-11-10

结论：P2首次真实关联与输出链路通过，主交付为`p2-60s-lowconf`。标准ByteTrack实际产生跨帧ID、JSONL及可解码视频，代表性人物ID有实际连续性；整体身份质量仍有明显碎片化和混淆，不宣称整场稳定跟踪或正式身份指标已通过。停止于P2，不开展P3。

## 实现、输入和固定参数

- 实际实现：`ultralytics.trackers.byte_tracker.BYTETracker`，Ultralytics **8.3.228**内置ByteTrack，无独立ByteTrack包版本；lap **0.5.12**，NumPy2.2.6、SciPy1.17.1、PyAV18.1.0。直接调用真实Kalman XYAH预测、LAPJV全局分配和高低分两阶段关联，不手写最近邻或改名模拟算法。[接口说明](https://docs.ultralytics.com/reference/trackers/byte_tracker/)，[ByteTrack原作者说明](https://github.com/FoundationVision/ByteTrack)。
- 已通读本机该版本BYTETracker、matching、Boxes及官方调用方，使用NumPy-backed Boxes输入、真实返回的`[xyxy,track_id,score,class,index]`。高/低分子数组的index分别映射回原检测索引，并以confidence/class复核。实现文件SHA256：`b50ee763b6760e71b2e6e2116bb5e85484f87cf8ac7fc165fa66482e9cae9a0d`。
- 原服务`ai-service/app/tracker.py`的历史Mock/逐框新ID适配不用于真实P2；真实路径选择新标准实现，旧适配留待后续服务接入时清理，不混合两套语义。本轮不改前后端或已有服务功能。
- 样片仍为2026赛事“东方语学院VS叽歪国传第七轮”第1段，MatchID `E2026-D4064D929E`、PartID `E2026-D4064D929E-P001`，源相对600—660秒，1759帧。首轮完整复用P1已有17468框，没有先跑YOLO；基线碎片化明显后，仅补跑一次同窗conf=0.10的真实CUDA检测，得到29920框，PTS及原始frameIndex重新验证通过。
- 固定配置`config/bytetrack-p2.yaml`：high=0.25、low=0.10、new_track=0.25、track_buffer=30、match_thresh=0.8、fuse_score=true。该版本第二阶段IoU代价阈值0.5、未确认轨迹阈值0.7由上游实现固定，未改算法代码。
- 关联在CPU上执行，未再次加载YOLO权重；单次低阈值检测补跑才使用GPU。根据实际1759帧PTS跨度估计frame_rate=29.311388，仅供上游lost-buffer缩放，得到max_time_lost=29帧。Kalman保持上游逐帧一步的模型，并非按可变秒间隔改写动力学；输出时间不由FPS重建。

## 三个有依据的运行及取舍

| 运行 | YOLO conf | fuse_score | 输出轨迹片段 | 有轨迹帧数/1759 | ID首末跨度中位 | 最大跨度 | 取舍 |
|---|---:|---|---:|---:|---:|---:|---|
| 原P1基线 | 0.25 | true | 465 | 1754 | 0.400s | 21.200s | 保留对照 |
| 主交付 | 0.10 | true | 436 | 1754 | 0.533s | 21.200s | 采用 |
| 关闭score融合对照 | 0.10 | false | 363 | 1759 | 0.833s | 34.510s | 人工拒选：混接身份 |

原P1截断0.25使默认低分区间(0.1,0.25)没有候选，基线465条轨迹、中位跨度0.4秒、无遮挡人物出现编号交替。一次conf0.10补跑产生12452个低分候选观测，主结果1810次输出关联来自低分区间。没有只因理论可能改善就批量重跑或网格搜索。

低阈值结果仍出现无遮挡人物2389→2542→2389。对本地score融合公式做一次有针对性的关闭对照，其他参数及检测输入不变。虽然片段数降到363、最大跨度升到34.51秒，人工却看到ID418从蓝衣人物串到黑衣4号再到其他蓝衣人物，ID482从蓝衣9号串到黄衣裁判；这些不是正式ID Switch计数，但足以拒绝该对照为主结果。因此最终恢复官方fuse_score=true，采用436条轨迹的主交付，不把“更少/更长”冒充身份更准。弃选结果保留于本机`p2-60s-score-fusion-off-rejected`，run.json已标记人工拒选。

## 主交付统计和人工观察

2026-11-10 P3输入复核更正：主交付真实观测数为15644，原报告把弃选的fuse_score=false对照19317误写到这里。依据主结果tracks.jsonl、run.json及validation.json更正两处文字；没有重新关联或修改轨迹数据。

- 1759帧全部写入tracks.jsonl和tracked.mp4，1754帧有输出轨迹，5帧的tracks为空而非被跳过；15644条轨迹观测、436条实际输出轨迹片段，其中412条有多帧观测。轨迹数按出现过的segmentId/trackId组合计，不等于最大ID或真实人数，未确认候选可能消耗ID但不输出。
- ID首末PTS跨度最小0秒（单次观测）、中位0.533秒、最大21.2秒；run.json逐条保留观测帧数、首末时间、跨度、最长连续帧数和缺测后再出现位置。跨度可能含缺测，不是已证明同一身份的持续时间。
- 关联计算2.201秒；总42.735秒含解码、绘图、H.264编码。主输入SHA256：`c260463095ef7bd5cc6e7215bba9bcff125a666c1e72ed90d229d64239eb141e`。时间属于单次实际运行，不是性能压力测试。
- 人工对照原视频裁剪：主结果ID2224在黑衣4号身上跨多个时刻一致，clip1230有框、1231无框、1232以同ID恢复；此处人物仍可见，因此只称短缺测恢复，不谎称证明遮挡恢复。ID2329在蓝衣100号人物上代表帧一致，最长连续576帧、约19.567秒，跨度19.8秒。证据在本机manual-review.jpg。
- 交叉/遮挡密集处仍有多框竞争、ID交替和身份歧义；右缘人物有短暂无输出后重现，远端小目标检测抖动和漏检造成短轨迹。不能保证真实遮挡、出画再入画或快速摇镜后的身份可靠恢复。没有相机运动补偿或ReID扩展，不以本次观察宣称问题已解决。
- 没有人工逐帧身份真值，ID Switch/MOTA/IDF1均为null/NOT_AVAILABLE，不编造零错误或精度分数。531次缺测后同ID再出现只是程序观测，不是531次正确遮挡恢复。

## 输出结构、时序与复现

主结果目录：`artifacts/real-bytetrack/p2-60s-lowconf/`，包含`tracks.jsonl`、`tracked.mp4`、`run.json`、`validation.json`及本机代表图。tracked.mp4为H.264/yuv420p、1920×1080、约60.011167秒、无音轨。原片、权重、虚拟环境、跟踪视频和大量中间结果均不入Git。

JSONL每行一帧，保留matchId/partId/frameIndex/timeMs/sourcePts/sourceTimeBase等原字段，tracks数组保存trackId/trackKey/confidence/classId/x1/y1/x2/y2/detectionIndex/detectionBbox。x1等为真实ByteTrack的Kalman平滑框，detectionBbox为关联原检测框，不假装两者完全相同。空帧也保留，可直接按原PTS衔接后续处理。

每次运行新建tracker；每次只接受同一比赛同一part，混入其他part报错，需另起运行并重置。源frameIndex不连续或相邻PTS间隔超过1秒时重置，输出segmentId及含比赛/分段/segment/trackId的trackKey，避免把重置后的同编号当作同一人。本样片未触发重置。默认不建立跨缺失分段人员身份；本轮不检测语义镜头切换，也不重写上游Kalman模型。

本机将FRAMECALL_EVENT_ROOT设为对应赛事真实目录后，从项目根执行（run-name必须新建）：

```powershell
.\ai-service\.venv-yolo\Scripts\python.exe -m pip install -r ai-service/requirements-bytetrack.txt
.\ai-service\.venv-yolo\Scripts\python.exe -B -X utf8 scripts/real-bytetrack.py --detections artifacts/real-yolo-smoke/p2-lowconf-60s/detections.jsonl --event-root "$env:FRAMECALL_EVENT_ROOT" --run-name p2-reproduce
```

已有数据复现不再运行YOLO；若需重新准备相同低分输入，P1脚本使用原part-id、start=600、duration=60、conf=0.10及新run-name。配置、依赖和运行参数已固定；不加载替代Mock，错误显式FAILED/exit1。

实际必要检查：lap安装和pip check退出0；P1低分真实检测/原片PTS校验退出0；基线、低分、弃选对照三次真实ByteTrack运行均exit0（程序结束不等于人工接受）；三个视频全部逐帧解码，源ID/PTS及每条轨迹的原检测索引、score、class对应验证均exit0。主结果1759帧、15644观测全部通过，输出PTS误差小于0.001ms。原片大小/mtime前后不变。

`python -m unittest discover -s scripts/tests -p test_real_bytetrack.py -v`在专用环境运行3项全部通过：真实ByteTrack低分二阶段索引及短缺测恢复、缺帧重置命名空间、不同part不能静默串接。这些小型合成边界输入仅验证适配业务语义，不计入真实足球统计；未跑全套历史测试。Git起点7622e0a，与远端同步后局部开发；公开提交仅脚本/必要配置依赖/测试/本报告/项目日志，未开展P3。
