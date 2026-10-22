import { existsSync } from "node:fs";
import { mkdir, readFile, readdir, stat, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(scriptDir, "..");
const supportedSplits = ["train", "val", "test", "demo"];

const args = parseArgs(process.argv.slice(2));
if (args.help) {
  printHelp();
  process.exit(0);
}

const inputPath = args.input
  ? path.resolve(process.cwd(), args.input)
  : await findLatestAnnotationJson();
const outputDir = path.resolve(process.cwd(), args.out || path.join(projectRoot, "storage", "experiments"));

const dataset = await loadDataset(inputPath);
const report = buildReport(dataset, inputPath);
await mkdir(outputDir, { recursive: true });

const stamp = new Date().toISOString().replace(/[:.]/g, "-");
const baseName = `${safeName(report.dataset.datasetId || "annotation")}-${stamp}`;
const jsonReportPath = path.join(outputDir, `${baseName}.stats.json`);
const markdownReportPath = path.join(outputDir, `${baseName}.stats.md`);

await writeFile(jsonReportPath, JSON.stringify(report, null, 2), "utf-8");
await writeFile(markdownReportPath, renderMarkdown(report), "utf-8");

console.log(JSON.stringify({
  inputPath,
  sourceFormat: report.sourceFormat,
  jsonReportPath,
  markdownReportPath,
  totals: report.totals,
  splits: Object.keys(report.bySplit),
}, null, 2));

function parseArgs(argv) {
  const parsed = {};
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === "--help" || arg === "-h") {
      parsed.help = true;
    } else if (arg === "--input" || arg === "-i") {
      parsed.input = argv[++index];
    } else if (arg === "--out" || arg === "-o") {
      parsed.out = argv[++index];
    } else if (!parsed.input) {
      parsed.input = arg;
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  return parsed;
}

function printHelp() {
  console.log(`Usage:
  node scripts/experiment-stats.mjs --input docs/examples/annotation_labels.experiment_sample.json
  node scripts/experiment-stats.mjs --input storage/annotations/labels.json --out storage/experiments

When --input is omitted, the newest annotation.v1 JSON file under storage/annotations is used.
The script writes both JSON and Markdown reports.`);
}

async function findLatestAnnotationJson() {
  const annotationDir = path.join(projectRoot, "storage", "annotations");
  if (!existsSync(annotationDir)) {
    throw new Error("No input provided and storage/annotations does not exist.");
  }
  const files = await readdir(annotationDir);
  const candidates = [];
  for (const file of files) {
    if (!file.toLowerCase().endsWith(".json")) {
      continue;
    }
    const fullPath = path.join(annotationDir, file);
    const info = await stat(fullPath);
    candidates.push({ fullPath, mtimeMs: info.mtimeMs });
  }
  candidates.sort((left, right) => right.mtimeMs - left.mtimeMs);
  for (const candidate of candidates) {
    try {
      const content = await readText(candidate.fullPath);
      const parsed = JSON.parse(content);
      if (parsed.schemaVersion === "annotation.v1") {
        return candidate.fullPath;
      }
    } catch {
      // Keep scanning; uploaded files can be user-provided and malformed.
    }
  }
  throw new Error("No annotation.v1 JSON file found under storage/annotations.");
}

async function loadDataset(inputPath) {
  const ext = path.extname(inputPath).toLowerCase();
  const content = await readText(inputPath);
  if (ext === ".csv") {
    return loadCsvDataset(content);
  }
  return loadJsonDataset(content);
}

function loadJsonDataset(content) {
  const root = JSON.parse(content);
  if (root.schemaVersion !== "annotation.v1") {
    throw new Error("JSON input must use schemaVersion=annotation.v1.");
  }
  if (!Array.isArray(root.samples)) {
    throw new Error("annotation.v1 JSON input must contain samples array.");
  }

  return {
    sourceFormat: "annotation-json",
    datasetId: text(root.datasetId),
    datasetName: text(root.datasetName),
    samples: root.samples.map((sample) => ({
      sampleId: text(sample.sampleId),
      split: normalizeSplit(sample.split),
      durationMs: numberOrZero(sample.durationMs),
      fps: numberOrNull(sample.fps),
      frameCount: numberOrNull(sample.frameCount),
      events: Array.isArray(sample.events)
        ? sample.events.map((event) => normalizeJsonEvent(event))
        : [],
    })),
  };
}

function normalizeJsonEvent(event) {
  const decision = event.decision || {};
  const boxes = Array.isArray(event.boxes)
    ? event.boxes
    : (Array.isArray(event.trackBoxes) ? event.trackBoxes : []);
  const tracks = Array.isArray(event.tracks) ? event.tracks : [];
  return {
    eventId: text(event.eventId || event.externalId),
    eventType: text(event.eventType),
    label: text(event.label),
    riskLevel: text(event.riskLevel),
    timestampMs: numberOrNull(event.timestampMs),
    keyframeFrameIndex: numberOrNull(event.keyframe?.frameIndex),
    keyframeTimestampMs: numberOrNull(event.keyframe?.timestampMs),
    toleranceFrames: numberOrNull(event.keyframe?.toleranceFrames),
    windowStartMs: numberOrNull(event.windowStartMs),
    windowEndMs: numberOrNull(event.windowEndMs),
    involvedTrackIds: Array.isArray(event.involvedTrackIds) ? event.involvedTrackIds.map(String) : [],
    expertDecision: text(decision.expertDecision),
    reviewResult: text(decision.reviewResult || event.label),
    reasonCode: text(decision.reasonCode),
    decisionConfidence: numberOrNull(decision.confidence ?? event.confidence),
    createdBy: text(event.createdBy),
    boxes: boxes.map((box) => ({
      label: text(box.label),
      team: text(box.team),
      confidence: numberOrNull(box.confidence),
    })),
    tracks: tracks.map((track) => ({
      trackId: text(track.trackId),
      observations: Array.isArray(track.observations) ? track.observations : [],
    })),
  };
}

function loadCsvDataset(content) {
  const rows = parseCsv(content).filter((row) => row.some((cell) => cell.trim() !== ""));
  if (rows.length === 0) {
    throw new Error("CSV input is empty.");
  }
  const headers = rows[0].map((header) => header.trim());
  const records = rows.slice(1).map((row) => Object.fromEntries(headers.map((header, index) => [header, row[index] || ""])));
  const samples = new Map();
  for (const record of records) {
    const sampleId = text(record.sampleId);
    const split = normalizeSplit(record.split);
    if (!samples.has(sampleId)) {
      samples.set(sampleId, { sampleId, split, durationMs: 0, fps: null, frameCount: null, events: [] });
    }
    samples.get(sampleId).events.push({
      eventId: text(record.eventId),
      eventType: text(record.eventType),
      label: text(record.label),
      riskLevel: "",
      timestampMs: numberOrNull(record.timestampMs),
      keyframeFrameIndex: numberOrNull(record.keyframeFrameIndex),
      keyframeTimestampMs: null,
      toleranceFrames: null,
      windowStartMs: numberOrNull(record.windowStartMs),
      windowEndMs: numberOrNull(record.windowEndMs),
      involvedTrackIds: text(record.involvedTrackIds).split(";").filter(Boolean),
      expertDecision: text(record.expertDecision),
      reviewResult: text(record.label),
      reasonCode: text(record.reasonCode),
      decisionConfidence: numberOrNull(record.confidence),
      createdBy: text(record.createdBy),
      boxes: [],
      tracks: [],
    });
  }

  return {
    sourceFormat: "annotation-csv",
    datasetId: text(records[0]?.datasetId),
    datasetName: "",
    samples: Array.from(samples.values()),
  };
}

function buildReport(dataset, inputPath) {
  const report = {
    generatedAt: new Date().toISOString(),
    inputPath,
    sourceFormat: dataset.sourceFormat,
    dataset: {
      datasetId: dataset.datasetId,
      datasetName: dataset.datasetName,
    },
    totals: {
      samples: 0,
      events: 0,
      durationMs: 0,
      durationMinutes: 0,
      boxes: 0,
      tracks: 0,
      trackObservations: 0,
    },
    bySplit: {},
    overall: createBucket(),
    notes: [],
  };

  for (const split of supportedSplits) {
    report.bySplit[split] = createBucket();
  }

  for (const sample of dataset.samples) {
    const split = normalizeSplit(sample.split);
    if (!report.bySplit[split]) {
      report.bySplit[split] = createBucket();
    }
    const splitBucket = report.bySplit[split];
    addSample(report.overall, sample);
    addSample(splitBucket, sample);
    report.totals.samples += 1;
    report.totals.durationMs += sample.durationMs || 0;

    for (const event of sample.events) {
      addEvent(report.overall, event);
      addEvent(splitBucket, event);
      report.totals.events += 1;
      report.totals.boxes += event.boxes.length;
      report.totals.tracks += event.tracks.length;
      report.totals.trackObservations += event.tracks.reduce((total, track) => total + track.observations.length, 0);
    }
  }

  report.totals.durationMinutes = round(report.totals.durationMs / 60000, 3);
  report.overall = finalizeBucket(report.overall);
  for (const [split, bucket] of Object.entries(report.bySplit)) {
    if (bucket.samples === 0 && bucket.events === 0) {
      delete report.bySplit[split];
    } else {
      report.bySplit[split] = finalizeBucket(bucket);
    }
  }

  if (dataset.sourceFormat === "annotation-csv") {
    report.notes.push("CSV input only contains event-level fields, so detection box and tracking observation statistics are unavailable.");
  }
  if (report.totals.events === 0) {
    report.notes.push("No events were found in the input.");
  }
  return report;
}

function createBucket() {
  return {
    samples: 0,
    events: 0,
    durationMs: 0,
    durationMinutes: 0,
    eventTypes: {},
    labels: {},
    reviewResults: {},
    expertDecisions: {},
    reasonCodes: {},
    detection: {
      boxes: 0,
      eventsWithBoxes: 0,
      samplesWithBoxes: 0,
      boxesByLabel: {},
      boxesByTeam: {},
      boxConfidence: createNumberAccumulator(),
    },
    tracking: {
      tracks: 0,
      eventsWithTracks: 0,
      samplesWithTracks: 0,
      uniqueTrackIds: {},
      observations: 0,
      visibility: {},
      observationsPerTrack: createNumberAccumulator(),
    },
    decisionConfidence: createNumberAccumulator(),
    keyframeToleranceFrames: createNumberAccumulator(),
    windowDurationMs: createNumberAccumulator(),
  };
}

function addSample(bucket, sample) {
  bucket.samples += 1;
  bucket.durationMs += sample.durationMs || 0;
  if (sample.events.some((event) => event.boxes.length > 0)) {
    bucket.detection.samplesWithBoxes += 1;
  }
  if (sample.events.some((event) => event.tracks.length > 0)) {
    bucket.tracking.samplesWithTracks += 1;
  }
}

function addEvent(bucket, event) {
  bucket.events += 1;
  addCount(bucket.eventTypes, event.eventType || "UNKNOWN");
  addCount(bucket.labels, event.label || "UNKNOWN");
  addCount(bucket.reviewResults, event.reviewResult || "UNKNOWN");
  addCount(bucket.expertDecisions, event.expertDecision || "UNKNOWN");
  addCount(bucket.reasonCodes, event.reasonCode || "UNKNOWN");
  addNumber(bucket.decisionConfidence, event.decisionConfidence);
  addNumber(bucket.keyframeToleranceFrames, event.toleranceFrames);

  if (event.windowStartMs !== null && event.windowEndMs !== null) {
    addNumber(bucket.windowDurationMs, Math.max(0, event.windowEndMs - event.windowStartMs));
  }

  if (event.boxes.length > 0) {
    bucket.detection.eventsWithBoxes += 1;
  }
  bucket.detection.boxes += event.boxes.length;
  for (const box of event.boxes) {
    addCount(bucket.detection.boxesByLabel, box.label || "UNKNOWN");
    addCount(bucket.detection.boxesByTeam, box.team || "UNKNOWN");
    addNumber(bucket.detection.boxConfidence, box.confidence);
  }

  if (event.tracks.length > 0) {
    bucket.tracking.eventsWithTracks += 1;
  }
  bucket.tracking.tracks += event.tracks.length;
  for (const track of event.tracks) {
    if (track.trackId) {
      bucket.tracking.uniqueTrackIds[track.trackId] = true;
    }
    bucket.tracking.observations += track.observations.length;
    addNumber(bucket.tracking.observationsPerTrack, track.observations.length);
    for (const observation of track.observations) {
      addCount(bucket.tracking.visibility, text(observation.visibility) || "UNKNOWN");
    }
  }
}

function finalizeBucket(bucket) {
  bucket.durationMinutes = round(bucket.durationMs / 60000, 3);
  bucket.decisionConfidence = finalizeNumber(bucket.decisionConfidence);
  bucket.keyframeToleranceFrames = finalizeNumber(bucket.keyframeToleranceFrames);
  bucket.windowDurationMs = finalizeNumber(bucket.windowDurationMs);
  bucket.detection.boxConfidence = finalizeNumber(bucket.detection.boxConfidence);
  bucket.tracking.uniqueTrackIds = Object.keys(bucket.tracking.uniqueTrackIds).length;
  bucket.tracking.observationsPerTrack = finalizeNumber(bucket.tracking.observationsPerTrack);
  return bucket;
}

function createNumberAccumulator() {
  return { count: 0, min: null, max: null, average: null, sum: 0 };
}

function addNumber(accumulator, value) {
  if (value === null || Number.isNaN(value)) {
    return;
  }
  accumulator.count += 1;
  accumulator.sum += value;
  accumulator.min = accumulator.min === null ? value : Math.min(accumulator.min, value);
  accumulator.max = accumulator.max === null ? value : Math.max(accumulator.max, value);
}

function finalizeNumber(accumulator) {
  if (accumulator.count === 0) {
    return { count: 0, min: null, max: null, average: null };
  }
  return {
    count: accumulator.count,
    min: round(accumulator.min, 4),
    max: round(accumulator.max, 4),
    average: round(accumulator.sum / accumulator.count, 4),
  };
}

function addCount(target, key) {
  target[key] = (target[key] || 0) + 1;
}

function renderMarkdown(report) {
  const lines = [];
  lines.push("# 标注实验统计报告");
  lines.push("");
  lines.push(`- 输入文件：\`${report.inputPath}\``);
  lines.push(`- 输入格式：\`${report.sourceFormat}\``);
  lines.push(`- 数据集：\`${report.dataset.datasetId || "UNKNOWN"}\`${report.dataset.datasetName ? ` / ${report.dataset.datasetName}` : ""}`);
  lines.push(`- 生成时间：${report.generatedAt}`);
  lines.push("");
  lines.push("## 总览");
  lines.push("");
  lines.push("| 指标 | 数值 |");
  lines.push("| --- | ---: |");
  lines.push(`| 样本数 | ${report.totals.samples} |`);
  lines.push(`| 事件数 | ${report.totals.events} |`);
  lines.push(`| 总时长（分钟） | ${report.totals.durationMinutes} |`);
  lines.push(`| 检测框数 | ${report.totals.boxes} |`);
  lines.push(`| 轨迹数 | ${report.totals.tracks} |`);
  lines.push(`| 轨迹观测数 | ${report.totals.trackObservations} |`);
  lines.push("");
  lines.push("## 按 Split 汇总");
  lines.push("");
  lines.push("| split | 样本 | 事件 | 时长(min) | FOUL | HANDBALL | OFFSIDE | 检测框 | 轨迹 | 复核确认 | 复核驳回 | 复核不确定 | 平均信心 |");
  lines.push("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |");
  for (const [split, bucket] of Object.entries(report.bySplit)) {
    lines.push([
      `| ${split}`,
      bucket.samples,
      bucket.events,
      bucket.durationMinutes,
      bucket.eventTypes.FOUL || 0,
      bucket.eventTypes.HANDBALL || 0,
      bucket.eventTypes.OFFSIDE || 0,
      bucket.detection.boxes,
      bucket.tracking.tracks,
      bucket.reviewResults.CONFIRMED || 0,
      bucket.reviewResults.REJECTED || 0,
      bucket.reviewResults.UNCERTAIN || 0,
      bucket.decisionConfidence.average ?? "",
    ].join(" | ") + " |");
  }
  lines.push("");
  lines.push("## 事件类型");
  lines.push("");
  appendCountTable(lines, report.overall.eventTypes);
  lines.push("");
  lines.push("## 复核结果");
  lines.push("");
  appendCountTable(lines, report.overall.reviewResults);
  lines.push("");
  lines.push("## 检测与跟踪");
  lines.push("");
  lines.push(`- 检测框总数：${report.overall.detection.boxes}`);
  lines.push(`- 有检测框的事件数：${report.overall.detection.eventsWithBoxes}`);
  lines.push(`- 检测框平均置信度：${report.overall.detection.boxConfidence.average ?? "N/A"}`);
  lines.push(`- 轨迹总数：${report.overall.tracking.tracks}`);
  lines.push(`- 唯一轨迹 ID 数：${report.overall.tracking.uniqueTrackIds}`);
  lines.push(`- 轨迹观测总数：${report.overall.tracking.observations}`);
  lines.push(`- 每条轨迹平均观测数：${report.overall.tracking.observationsPerTrack.average ?? "N/A"}`);
  if (report.notes.length > 0) {
    lines.push("");
    lines.push("## 注意");
    lines.push("");
    for (const note of report.notes) {
      lines.push(`- ${note}`);
    }
  }
  lines.push("");
  return lines.join("\n");
}

function appendCountTable(lines, counts) {
  lines.push("| 项 | 数量 |");
  lines.push("| --- | ---: |");
  for (const [key, value] of Object.entries(counts)) {
    lines.push(`| ${key} | ${value} |`);
  }
}

function parseCsv(content) {
  const rows = [];
  let row = [];
  let cell = "";
  let quoted = false;
  for (let index = 0; index < content.length; index += 1) {
    const char = content[index];
    const next = content[index + 1];
    if (quoted) {
      if (char === "\"" && next === "\"") {
        cell += "\"";
        index += 1;
      } else if (char === "\"") {
        quoted = false;
      } else {
        cell += char;
      }
    } else if (char === "\"") {
      quoted = true;
    } else if (char === ",") {
      row.push(cell);
      cell = "";
    } else if (char === "\n") {
      row.push(cell.replace(/\r$/, ""));
      rows.push(row);
      row = [];
      cell = "";
    } else {
      cell += char;
    }
  }
  if (cell.length > 0 || row.length > 0) {
    row.push(cell.replace(/\r$/, ""));
    rows.push(row);
  }
  return rows;
}

async function readText(filePath) {
  return stripBom(await readFile(filePath, "utf-8"));
}

function stripBom(content) {
  return content.charCodeAt(0) === 0xfeff ? content.slice(1) : content;
}

function normalizeSplit(value) {
  const split = text(value).toLowerCase();
  return split || "unknown";
}

function text(value) {
  return value === null || value === undefined ? "" : String(value).trim();
}

function numberOrNull(value) {
  if (value === null || value === undefined || value === "") {
    return null;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function numberOrZero(value) {
  return numberOrNull(value) ?? 0;
}

function round(value, digits) {
  if (value === null || Number.isNaN(value)) {
    return null;
  }
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor;
}

function safeName(value) {
  return String(value).replace(/[^a-zA-Z0-9._-]+/g, "-").replace(/^-+|-+$/g, "") || "annotation";
}
