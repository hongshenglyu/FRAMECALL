# CONTACT 事件级评价

```powershell
node scripts/evaluate-contact.mjs docs/examples/contact-evaluation.synthetic.json
node --test scripts/tests/test-evaluate-contact.mjs
```

CLI 读取一个 JSON，向 stdout 输出 JSON，输入非法时退出码为 1。仓库示例为 [SYNTHETIC 合成夹具](examples/contact-evaluation.synthetic.json)。历史 `evaluate-predictions.mjs` 继续用于旧 `annotation.v1` 结果。

## 输入和来源

- 顶层 `schema` 为 `framecall.contact-evaluation.v1`。夹具用 `kind: SYNTHETIC`。正式包用 `ADJUDICATED`，必须填写 `protocol.frozenRef` 与显式 `protocol.toleranceMs`，每次评价同一 split 的单个方法。合成夹具默认 2000 毫秒。正式容差仍待开发试标核定。
- `matches` 每项包含唯一 `matchId`、`split`（DEVELOPMENT、VALIDATION、TEST）、`segments`、`truth`、`predictions`。正式包需填写 `coverageReviewRef`（全覆盖人工核查记录）和 `predictionRunRef`（已完成的预测运行记录）。每个有标注覆盖的段也要填写对应 `predictionRunRef`。全负场或零候选运行可分别有空 `truth` 或 `predictions`。记录引用由实验人员核实。
- 每个 `segment` 指定 `partId`、比赛内唯一 `segmentId`、连续性编号 `continuityId`、视频流 `timeBase`、源整数 PTS 范围 `[sourceStartPts, sourceEndPts)` 及起点 `matchStartMs`。换算公式为 `matchStartMs + (sourcePts - sourceStartPts) × timeBase × 1000`。先去除重叠，同一连续编号只用于原片证实可直接衔接的区间。实际断点使用不同编号。每段 `annotatedRanges` 为人工连续查阅的源 PTS 半开区间数组，可选 `invalidRanges` 标记无有效比赛画面的区间。其余为未覆盖。
- 源点统一写成 `{partId, segmentId, sourcePts}`。人工记录含 `id, sceneType, visibility, start, end`。正式记录的 `decision` 填 `source: LICENSED_REFEREE_ADJUDICATION`、不同自然人对应的脱敏 `annotatorId` 与 `reviewerId`、`recordRef`。只有争抢 `CONTEST` 中的 `VISIBLE_CONTACT` 可作为正例，必须记录 `firstContact`，且位于 `[start,end)` 内。其余记录分别用 `NO_VISIBLE_CONTACT`、`INSUFFICIENT_VIEW`、`OUT_OF_SCOPE`。裁判身份、资格及裁定需由项目方核实。
- 预测包含 `id`、`eventType: CONTACT`、`peak` 和可空的 `playableClipId`。对应可播片段在同场 `clips` 内声明唯一 `id`、已验证可播的 `mediaRef`、源 `start` 与排他 `end`。关联必须覆盖峰值。实际播放及帧区间由制包者核验。
- 候选转换可调用 `convertContactCandidates(run.methods.combined, {matchId, partId, segmentId, sourceSegmentId, timeBase})`。每次仅传已确认的原片段候选，`sourceSegmentId` 来自 P3 产物，`segmentId` 对应评价包。转换保留 `peakSourcePts`，默认无片段关联。上传裁切样片需先取得原片映射。人工裁定可按上述接口录入 JSON，P5 的试标导出保留作复核材料。

## 统计口径

先排除无效和未标注区域内的预测。对有效预测按比赛和连续时间轴，在容差内最大化一对一配对数，再最小化绝对时间误差之和。时间相同按 ID 排序，匹配结果确定。同一时间附近有多个争抢且无法靠时间区分时，正式冻结前须用人工区域或参与者信息确认范围，歧义区按协议登记为存疑。本入口按时间匹配。

未匹配的有效接触计 FN。未匹配预测若落在已裁定的画面不足或范围外区间，单列其原因，其余计 FP（包括无接触争抢）。输出逐场及微平均的 TP、FP、FN、Precision、Recall、F1，零分母为 JSON null。逐记录保留源点、比赛时间、配对和误差。

`reviewMedia.status` 现为 `NOT_COMPUTED`。已保留片段关联及其映射边界，后续可统计交付片段完整性和按有效区间裁边后的窗口并集时长。全候选匹配结果只用于事件级指标。
