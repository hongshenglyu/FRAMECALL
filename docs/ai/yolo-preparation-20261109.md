# P1 YOLOv8准备与执行暂缓｜2026-11-09

本轮按用户补充限制优先保护另一项目的训练：仅做环境元数据查询和权重准备，真实推理未执行，P1未验收。没有加载模型、导入torch/ultralytics、查询CUDA设备、分配显存、执行GPU压力测试或干预其他项目进程；没有以Mock代替真实结果。原有AI入口仍有fallback和模拟评分，本轮没有调用或修改它，也未将YOLO接入Spring任务链。

## 环境与资源

| 项目 | 系统Python | ai-service现有虚拟环境 |
|---|---|---|
| Python | 3.11.6 | 3.11.6 |
| PyTorch / torchvision / Ultralytics | 未安装 | 未安装 |
| OpenCV | opencv-python-headless 5.0.0.93，cv2模块可定位 | 未安装 |
| PyAV | 18.1.0 | 未安装 |
| Pillow | 11.3.0 | 11.0.0 |
| imageio-ffmpeg | 0.6.0 | 未安装 |

版本由importlib.metadata读取，并用find_spec确认torch/ultralytics不可定位；未为检查版本而导入模型框架。首轮只查opencv-python未命中，补查发行包变体后确认系统已有headless版本，以上表为准。ffmpeg/ffprobe不在当前PATH，系统另有imageio-ffmpeg包；未运行其二进制能力测试，P0只读容器元数据使用现有PyAV，不安装新工具。

只读系统采样：总内存约15.19GiB，可用内存约1.3—1.5GiB；后一次CPU采样64%、磁盘活动1%（约9.4MB/s）。这些是瞬时系统计数，不是对训练性能影响的压力测试。因内存余量不足、CPU已有负载且缺推理依赖，本轮不安装大型torch依赖，不创建新推理环境，不跑CPU推理。其他项目的环境和训练保持原状。

| 未执行项 | 状态 |
|---|---|
| CUDA设备/驱动/显存可用性测试 | DEFERRED_GPU_BUSY |
| CUDA模型加载、真实GPU推理或训练 | DEFERRED_GPU_BUSY |
| GPU压力测试、CUDA显存分配测试 | DEFERRED_GPU_BUSY |
| 可选CPU模型加载与少量真实帧推理 | DEFERRED_RESOURCE_PROTECTION |
| 大型推理依赖安装 | DEFERRED_RESOURCE_PROTECTION |
| 真实检测框、可视化和人工观察 | 未产出，依赖真实推理；不标成功或模型失败 |

实际设备：NONE；处理真实推理帧数0，检测数/推理耗时不适用，没有可打开的检测可视化。这不是YOLO执行失败，也不满足P1真实检测验收。

## 已准备权重

- 模型：YOLOv8n，COCO预训练detect，未来先保留person(classId 0)，不能自动称为球员/裁判精确身份。
- 来源：[Ultralytics官方v8.3.0资产](https://github.com/ultralytics/assets/releases/download/v8.3.0/yolov8n.pt)，asset ID 195719301；这是资产发布标签，不冒充本机Ultralytics安装版本或checkpoint内部训练版本。
- 本机忽略位置：`models/yolo/v8.3.0/yolov8n.pt`，6,549,796 bytes。模型不提交GitHub。
- SHA256：`f59b3d833e2ff32e194b5bb8e08d211dc7c5bdf144b90d2c8412c47ccfc83b36`。
- 验证：官方HTTPS来源、API声明大小一致、本地SHA256、ZIP CRC通过；上游asset未提供digest，因此没有声称与官方发布摘要逐字比对。未反序列化/加载权重到CPU或GPU。
- 下载限速512KiB/s，使用本机已有代理，未写入代理凭据或改变其他项目网络配置。
- 上游许可登记为AGPL-3.0；来源见[官方模型说明](https://docs.ultralytics.com/models/yolov8/)及[许可说明](https://www.ultralytics.com/license)。仅登记本地研究准备所用上游许可，不据此宣称整项目商用许可已核实。
- 可复用资产信息保存于`config/yolov8n.weights.json`，仅含相对路径、公开来源和摘要；不触发自动加载。

## 实际检查与退出码

| 命令/检查 | 退出码/结果 |
|---|---|
| `git fetch origin`、`git merge --ff-only origin/dev/real-ai-pipeline` | 0；工作区起初干净，从fcf354d快进到402acc2，只新增V2.8计划 |
| 两套Python的`importlib.metadata`版本查询、只读CIM资源查询 | 0；以上版本/采样，不调用CUDA |
| `python -B -X utf8 .codex-work/p0p1-20261109/prepare_weights.py` | 0；下载、大小/SHA/CRC验证通过，未加载模型 |
| `python -B -X utf8 .codex-work/p0p1-20261109/update_data.py` 首轮 | 1；旧2025半决赛第3段根路径失效，断言在写CSV前停止，未产出半成品 |
| 同一增量命令修订后重跑 | 0；只补核对同场目录一个候选路径，大小/时长/FPS匹配，保留原ID；39组97段一致 |
| `python -B -X utf8 .codex-work/p0p1-20261109/validate.py` | 首轮1：盘符匹配规则误匹配公开https URL；修正词边界后重跑0，未发现私人路径/令牌模式 |
| CPU/GPU真实推理、GPU内存/压力测试 | NOT_RUN；按上表暂缓，不计失败或通过 |

P0当前报告见`docs/data/dataset-audit.md`。V2.8 Markdown已从远端取得；项目计划目录及Downloads顶层未找到配对V2.8 Excel，不阻塞本轮准备，不编造文件。

## 后续最短工作

先等用户允许使用GPU、或CPU/内存资源有足够余量，再在FRAMECALL隔离环境安装并固定实际torch/Ultralytics版本，使用本次固定权重完成一段真实比赛15—30秒抽样的YOLO独立推理。按源PTS保存MatchID/PartID/frameIndex/timeMs/classId/confidence/bbox与本机画框证据，显式选定设备，真实失败不回落Mock；验证通过后才考虑ByteTrack。当前样片未选择、未解码，不把其他训练完成时间或未来推理结果写成已确认事实。
