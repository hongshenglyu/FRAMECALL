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

const truthPath = args.truth
  ? path.resolve(process.cwd(), args.truth)
  : await findLatestAnnotationJson();
const predictionPath = args.prediction
  ? path.resolve(process.cwd(), args.prediction)
  : await findLatestAnalysisResult();
const outputDir = path.resolve(process.cwd(), args.out || path.join(projectRoot, "storage", "experiments"));
const options = {
  toleranceMs: numberArg(args.toleranceMs, 3000),
  fps: numberArg(args.fps, 25),
  includeRejected: Boolean(args.includeRejected),
  confidenceThreshold: numberArg(args.confidenceThreshold, 0),
  sampleId: args.sampleId || "",
};

const truth = await loadTruth(truthPath, options);
const predictions = await loadPredictions(predictionPath, truth, options);
const report = evaluate(truth, predictions, predictionPath, options);

await mkdir(outputDir, { recursive: true });
const stamp = new Date().toISOString().replace(/[:.]/g, "-");
const baseName = `${safeName(truth.datasetId || "annotation")}-prediction-eval-${stamp}`;
const jsonReportPath = path.join(outputDir, `${baseName}.json`);
const markdownReportPath = path.join(outputDir, `${baseName}.md`);

await writeFile(jsonReportPath, JSON.stringify(report, null, 2), "utf-8");
await writeFile(markdownReportPath, renderMarkdown(report), "utf-8");

console.log(JSON.stringify({
  truthPath,
  predictionPath,
  jsonReportPath,
  markdownReportPath,
  options: report.options,
  overall: report.overall,
}, null, 2));

function parseArgs(argv) {
  const parsed = {};
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === "--help" || arg === "-h") {
      parsed.help = true;
    } else if (arg === "--truth" || arg === "-t") {
      parsed.truth = argv[++index];
    } else if (arg === "--prediction" || arg === "-p") {
      parsed.prediction = argv[++index];
    } else if (arg === "--out" || arg === "-o") {
      parsed.out = argv[++index];
    } else if (arg === "--tolerance-ms") {
      parsed.toleranceMs = argv[++index];
    } else if (arg === "--fps") {
      parsed.fps = argv[++index];
    } else if (arg === "--confidence-threshold") {
      parsed.confidenceThreshold = argv[++index];
    } else if (arg === "--sample-id") {
      parsed.sampleId = argv[++index];
    } else if (arg === "--include-rejected") {
      parsed.includeRejected = true;
    } else if (!parsed.truth) {
      parsed.truth = arg;
    } else if (!parsed.prediction) {
      parsed.prediction = arg;
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  return parsed;
}

function printHelp() {
  console.log(`Usage:
  node scripts/evaluate-predictions.mjs --truth docs/examples/annotation_labels.experiment_sample.json --prediction docs/examples/predictions
  node scripts/evaluate-predictions.mjs --truth labels.json --prediction analysis_result.json --sample-id clip-0001

Options:
  --tolerance-ms 3000          Max timestamp error for a direct keyframe match.
  --fps 25                     Fallback FPS for keyframe error in frames.
  --confidence-threshold 0.5   Ignore predictions below this confidence.
  --include-rejected           Treat REJECTED truth events as positives.

When omitted, truth defaults to the newest annotation.v1 file under storage/annotations,
and prediction defaults to the newest storage/tasks/**/analysis_result.json.`);
}

async function findLatestAnnotationJson() {
  const annotationDir = path.join(projectRoot, "storage", "annotations");
  const candidates = await jsonFilesByMtime(annotationDir);
  for (const candidate of candidates) {
    try {
      const parsed = JSON.parse(await readText(candidate.fullPath));
      if (parsed.schemaVersion === "annotation.v1") {
        return candidate.fullPath;
      }
    } catch {
      // Continue scanning user-uploaded files.
    }
  }
  throw new Error("No annotation.v1 JSON file found under storage/annotations.");
}

async function findLatestAnalysisResult() {
  const tasksDir = path.join(projectRoot, "storage", "tasks");
  const files = await findFiles(tasksDir, "analysis_result.json");
  if (files.length === 0) {
    throw new Error("No analysis_result.json found under storage/tasks.");
  }
  const enriched = [];
  for (const file of files) {
    const info = await stat(file);
    enriched.push({ fullPath: file, mtimeMs: info.mtimeMs });
  }
  enriched.sort((left, right) => right.mtimeMs - left.mtimeMs);
  return enriched[0].fullPath;
}

async function jsonFilesByMtime(dir) {
  if (!existsSync(dir)) {
    return [];
  }
  const files = await readdir(dir);
  const candidates = [];
  for (const file of files) {
    if (!file.toLowerCase().endsWith(".json")) {
      continue;
    }
    const fullPath = path.join(dir, file);
    const info = await stat(fullPath);
    candidates.push({ fullPath, mtimeMs: info.mtimeMs });
  }
  return candidates.sort((left, right) => right.mtimeMs - left.mtimeMs);
}

async function findFiles(dir, targetName) {
  if (!existsSync(dir)) {
    return [];
  }
  const found = [];
  const entries = await readdir(dir, { withFileTypes: true });
  for (const entry of entries) {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      found.push(...await findFiles(fullPath, targetName));
    } else if (entry.isFile() && entry.name === targetName) {
      found.push(fullPath);
    }
  }
  return found;
}

async function loadTruth(filePath, options) {
  const root = JSON.parse(await readText(filePath));
  if (root.schemaVersion !== "annotation.v1") {
    throw new Error("Truth input must use schemaVersion=annotation.v1.");
  }
  if (!Array.isArray(root.samples)) {
    throw new Error("Truth input must contain samples array.");
  }

  const samples = new Map();
  const events = [];
  for (const sample of root.samples) {
    const sampleId = text(sample.sampleId);
    const split = normalizeSplit(sample.split);
    const fps = numberOrNull(sample.fps) ?? options.fps;
    const durationMs = numberOrZero(sample.durationMs);
    samples.set(sampleId, { sampleId, split, fps, durationMs });
    for (const event of Array.isArray(sample.events) ? sample.events : []) {
      const label = text(event.label || event.decision?.reviewResult);
      if (!options.includeRejected && label === "REJECTED") {
        continue;
      }
      events.push({
        id: text(event.eventId || `${sampleId}-truth-${events.length + 1}`),
        sampleId,
        split,
        fps,
        eventType: text(event.eventType),
        label,
        timestampMs: eventTime(event),
        windowStartMs: numberOrNull(event.windowStartMs),
        windowEndMs: numberOrNull(event.windowEndMs),
        keyframeFrameIndex: numberOrNull(event.keyframe?.frameIndex),
      });
    }
  }

  return {
    datasetId: text(root.datasetId),
    datasetName: text(root.datasetName),
    samples,
    events,
    totalDurationMs: Array.from(samples.values()).reduce((total, sample) => total + sample.durationMs, 0),
  };
}

async function loadPredictions(inputPath, truth, options) {
  const files = await predictionFiles(inputPath);
  const predictions = [];
  const notes = [];
  for (const file of files) {
    const root = JSON.parse(await readText(file));
    const rootSampleId = text(root.sampleId || root.videoSampleId || root.videoId || options.sampleId);
    const inferredSampleId = rootSampleId || inferSampleId(file, truth);
    if (!rootSampleId && inferredSampleId) {
      notes.push(`Prediction file ${file} has no sampleId; inferred ${inferredSampleId}.`);
    }

    const events = Array.isArray(root.events) ? root.events : [];
    for (const event of events) {
      const sampleId = text(event.sampleId || rootSampleId || inferredSampleId);
      const confidence = numberOrNull(event.confidence) ?? 0;
      if (confidence < options.confidenceThreshold) {
        continue;
      }
      const sample = truth.samples.get(sampleId);
      predictions.push({
        id: text(event.externalId || event.eventId || `${path.basename(file)}-${predictions.length + 1}`),
        sourcePath: file,
        sampleId,
        split: sample?.split || "unknown",
        fps: sample?.fps || options.fps,
        eventType: text(event.eventType),
        confidence,
        timestampMs: eventTime(event),
        windowStartMs: numberOrNull(event.windowStartMs),
        windowEndMs: numberOrNull(event.windowEndMs),
      });
    }
  }
  return { events: predictions, notes };
}

async function predictionFiles(inputPath) {
  const info = await stat(inputPath);
  if (info.isDirectory()) {
    const files = await findFiles(inputPath, "analysis_result.json");
    if (files.length === 0) {
      throw new Error(`No analysis_result.json files found under ${inputPath}.`);
    }
    return files.sort();
  }
  return [inputPath];
}

function inferSampleId(file, truth) {
  const stem = path.basename(path.dirname(file));
  if (truth.samples.has(stem)) {
    return stem;
  }
  if (truth.samples.size === 1) {
    return Array.from(truth.samples.keys())[0];
  }
  return stem;
}

function evaluate(truth, predictionSet, predictionPath, options) {
  const unmatchedPredictions = new Set(predictionSet.events.map((_, index) => index));
  const matches = [];
  const falseNegatives = [];

  const truthEvents = [...truth.events].sort(compareEvents);
  for (const truthEvent of truthEvents) {
    let best = null;
    for (const predictionIndex of unmatchedPredictions) {
      const prediction = predictionSet.events[predictionIndex];
      const candidate = scoreMatch(truthEvent, prediction, options);
      if (!candidate.matched) {
        continue;
      }
      if (!best || candidate.score < best.score) {
        best = { predictionIndex, prediction, ...candidate };
      }
    }
    if (best) {
      unmatchedPredictions.delete(best.predictionIndex);
      matches.push({
        sampleId: truthEvent.sampleId,
        split: truthEvent.split,
        eventType: truthEvent.eventType,
        truthEventId: truthEvent.id,
        predictionId: best.prediction.id,
        truthTimestampMs: truthEvent.timestampMs,
        predictedTimestampMs: best.prediction.timestampMs,
        keyframeErrorMs: best.errorMs,
        keyframeErrorFrames: round(best.errorFrames, 3),
        confidence: best.prediction.confidence,
        matchReason: best.reason,
      });
    } else {
      falseNegatives.push(truthEvent);
    }
  }

  const falsePositives = Array.from(unmatchedPredictions).map((index) => predictionSet.events[index]);
  const report = {
    generatedAt: new Date().toISOString(),
    truthPath,
    predictionPath,
    dataset: {
      datasetId: truth.datasetId,
      datasetName: truth.datasetName,
    },
    options: {
      toleranceMs: options.toleranceMs,
      fps: options.fps,
      includeRejected: options.includeRejected,
      confidenceThreshold: options.confidenceThreshold,
    },
    overall: finalizeBucket(makeBucket(), truth.totalDurationMs),
    bySplit: {},
    byEventType: {},
    matches,
    falseNegatives: falseNegatives.map(serializeTruthEvent),
    falsePositives: falsePositives.map(serializePredictionEvent),
    notes: [...predictionSet.notes],
  };

  const buckets = {
    overall: makeBucket(),
    split: {},
    eventType: {},
  };
  for (const split of supportedSplits) {
    buckets.split[split] = makeBucket();
  }

  for (const truthEvent of truth.events) {
    addTruth(buckets.overall, truthEvent);
    addTruth(bucketFor(buckets.split, truthEvent.split), truthEvent);
    addTruth(bucketFor(buckets.eventType, truthEvent.eventType), truthEvent);
  }
  for (const prediction of predictionSet.events) {
    addPrediction(buckets.overall, prediction);
    addPrediction(bucketFor(buckets.split, prediction.split), prediction);
    addPrediction(bucketFor(buckets.eventType, prediction.eventType), prediction);
  }
  for (const match of matches) {
    addMatch(buckets.overall, match);
    addMatch(bucketFor(buckets.split, match.split), match);
    addMatch(bucketFor(buckets.eventType, match.eventType), match);
  }

  const durationBySplit = splitDurations(truth);
  report.overall = finalizeBucket(buckets.overall, truth.totalDurationMs);
  for (const [split, bucket] of Object.entries(buckets.split)) {
    if (bucket.truthEvents === 0 && bucket.predictedEvents === 0) {
      continue;
    }
    report.bySplit[split] = finalizeBucket(bucket, durationBySplit[split] || 0);
  }
  for (const [eventType, bucket] of Object.entries(buckets.eventType)) {
    report.byEventType[eventType] = finalizeBucket(bucket, truth.totalDurationMs);
  }

  if (truth.totalDurationMs === 0) {
    report.notes.push("Truth samples have no durationMs; false positives per 3 minutes is unavailable.");
  }
  return report;
}

function scoreMatch(truthEvent, prediction, options) {
  if (truthEvent.sampleId !== prediction.sampleId) {
    return { matched: false };
  }
  if (truthEvent.eventType !== prediction.eventType) {
    return { matched: false };
  }
  if (truthEvent.timestampMs === null || prediction.timestampMs === null) {
    return { matched: false };
  }

  const errorMs = Math.abs(prediction.timestampMs - truthEvent.timestampMs);
  const errorFrames = errorMs / (1000 / (truthEvent.fps || options.fps));
  if (errorMs <= options.toleranceMs) {
    return {
      matched: true,
      score: errorMs,
      errorMs,
      errorFrames,
      reason: "timestamp",
    };
  }

  const overlapMs = windowOverlapMs(truthEvent, prediction);
  if (overlapMs > 0) {
    return {
      matched: true,
      score: errorMs + 100000 - overlapMs,
      errorMs,
      errorFrames,
      reason: "window-overlap",
    };
  }
  return { matched: false };
}

function windowOverlapMs(left, right) {
  if (left.windowStartMs === null || left.windowEndMs === null || right.windowStartMs === null || right.windowEndMs === null) {
    return 0;
  }
  return Math.max(0, Math.min(left.windowEndMs, right.windowEndMs) - Math.max(left.windowStartMs, right.windowStartMs));
}

function makeBucket() {
  return {
    truthEvents: 0,
    predictedEvents: 0,
    truePositives: 0,
    falsePositives: 0,
    falseNegatives: 0,
    precision: 0,
    recall: 0,
    f1: 0,
    falsePositivesPer3Min: null,
    keyframeErrorMs: numberAccumulator(),
    keyframeErrorFrames: numberAccumulator(),
    confidence: numberAccumulator(),
  };
}

function addTruth(bucket) {
  bucket.truthEvents += 1;
}

function addPrediction(bucket, prediction) {
  bucket.predictedEvents += 1;
  addNumber(bucket.confidence, prediction.confidence);
}

function addMatch(bucket, match) {
  bucket.truePositives += 1;
  addNumber(bucket.keyframeErrorMs, match.keyframeErrorMs);
  addNumber(bucket.keyframeErrorFrames, match.keyframeErrorFrames);
}

function finalizeBucket(bucket, durationMs) {
  bucket.falsePositives = bucket.predictedEvents - bucket.truePositives;
  bucket.falseNegatives = bucket.truthEvents - bucket.truePositives;
  bucket.precision = ratio(bucket.truePositives, bucket.predictedEvents);
  bucket.recall = ratio(bucket.truePositives, bucket.truthEvents);
  bucket.f1 = bucket.precision + bucket.recall === 0
    ? 0
    : round(2 * bucket.precision * bucket.recall / (bucket.precision + bucket.recall), 4);
  bucket.falsePositivesPer3Min = durationMs > 0
    ? round(bucket.falsePositives / (durationMs / 60000) * 3, 4)
    : null;
  bucket.keyframeErrorMs = finalizeNumber(bucket.keyframeErrorMs);
  bucket.keyframeErrorFrames = finalizeNumber(bucket.keyframeErrorFrames);
  bucket.confidence = finalizeNumber(bucket.confidence);
  return bucket;
}

function bucketFor(collection, key) {
  const safeKey = key || "unknown";
  if (!collection[safeKey]) {
    collection[safeKey] = makeBucket();
  }
  return collection[safeKey];
}

function splitDurations(truth) {
  const durations = {};
  for (const sample of truth.samples.values()) {
    durations[sample.split] = (durations[sample.split] || 0) + sample.durationMs;
  }
  return durations;
}

function renderMarkdown(report) {
  const lines = [];
  lines.push("# 预测结果评估报告");
  lines.push("");
  lines.push(`- 真值文件：\`${report.truthPath}\``);
  lines.push(`- 预测输入：\`${report.predictionPath}\``);
  lines.push(`- 数据集：\`${report.dataset.datasetId || "UNKNOWN"}\`${report.dataset.datasetName ? ` / ${report.dataset.datasetName}` : ""}`);
  lines.push(`- 匹配容差：${report.options.toleranceMs} ms`);
  lines.push(`- 生成时间：${report.generatedAt}`);
  lines.push("");
  lines.push("## 总览");
  lines.push("");
  appendMetricsTable(lines, report.overall);
  lines.push("");
  lines.push("## 按 Split 汇总");
  lines.push("");
  lines.push("| split | 真值 | 预测 | TP | FP | FN | Precision | Recall | F1 | 每3分钟误报 | 平均关键帧误差(ms) | 平均关键帧误差(frames) |");
  lines.push("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |");
  for (const [split, bucket] of Object.entries(report.bySplit)) {
    lines.push(row([
      split,
      bucket.truthEvents,
      bucket.predictedEvents,
      bucket.truePositives,
      bucket.falsePositives,
      bucket.falseNegatives,
      bucket.precision,
      bucket.recall,
      bucket.f1,
      bucket.falsePositivesPer3Min ?? "",
      bucket.keyframeErrorMs.average ?? "",
      bucket.keyframeErrorFrames.average ?? "",
    ]));
  }
  lines.push("");
  lines.push("## 按事件类型汇总");
  lines.push("");
  lines.push("| 类型 | 真值 | 预测 | TP | FP | FN | Precision | Recall | F1 |");
  lines.push("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |");
  for (const [eventType, bucket] of Object.entries(report.byEventType)) {
    lines.push(row([
      eventType,
      bucket.truthEvents,
      bucket.predictedEvents,
      bucket.truePositives,
      bucket.falsePositives,
      bucket.falseNegatives,
      bucket.precision,
      bucket.recall,
      bucket.f1,
    ]));
  }
  lines.push("");
  lines.push("## 匹配明细");
  lines.push("");
  lines.push("| sampleId | 类型 | 真值事件 | 预测事件 | 误差(ms) | 误差(frames) | 置信度 | 原因 |");
  lines.push("| --- | --- | --- | --- | ---: | ---: | ---: | --- |");
  for (const match of report.matches) {
    lines.push(row([
      match.sampleId,
      match.eventType,
      match.truthEventId,
      match.predictionId,
      match.keyframeErrorMs,
      match.keyframeErrorFrames,
      match.confidence,
      match.matchReason,
    ]));
  }
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

function appendMetricsTable(lines, bucket) {
  lines.push("| 指标 | 数值 |");
  lines.push("| --- | ---: |");
  lines.push(`| 真值事件 | ${bucket.truthEvents} |`);
  lines.push(`| 预测事件 | ${bucket.predictedEvents} |`);
  lines.push(`| TP | ${bucket.truePositives} |`);
  lines.push(`| FP | ${bucket.falsePositives} |`);
  lines.push(`| FN | ${bucket.falseNegatives} |`);
  lines.push(`| Precision | ${bucket.precision} |`);
  lines.push(`| Recall | ${bucket.recall} |`);
  lines.push(`| F1 | ${bucket.f1} |`);
  lines.push(`| 每 3 分钟误报数 | ${bucket.falsePositivesPer3Min ?? "N/A"} |`);
  lines.push(`| 平均关键帧误差 ms | ${bucket.keyframeErrorMs.average ?? "N/A"} |`);
  lines.push(`| 平均关键帧误差 frames | ${bucket.keyframeErrorFrames.average ?? "N/A"} |`);
}

function row(values) {
  return `| ${values.join(" | ")} |`;
}

function serializeTruthEvent(event) {
  return {
    sampleId: event.sampleId,
    split: event.split,
    eventType: event.eventType,
    eventId: event.id,
    timestampMs: event.timestampMs,
  };
}

function serializePredictionEvent(event) {
  return {
    sampleId: event.sampleId,
    split: event.split,
    eventType: event.eventType,
    predictionId: event.id,
    timestampMs: event.timestampMs,
    confidence: event.confidence,
    sourcePath: event.sourcePath,
  };
}

function compareEvents(left, right) {
  return left.sampleId.localeCompare(right.sampleId)
    || left.eventType.localeCompare(right.eventType)
    || (left.timestampMs ?? 0) - (right.timestampMs ?? 0);
}

function eventTime(event) {
  return numberOrNull(event.keyframe?.timestampMs) ?? numberOrNull(event.timestampMs);
}

function numberAccumulator() {
  return { count: 0, min: null, max: null, sum: 0 };
}

function addNumber(accumulator, value) {
  if (value === null || value === undefined || Number.isNaN(value)) {
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

function ratio(numerator, denominator) {
  return denominator === 0 ? 0 : round(numerator / denominator, 4);
}

async function readText(filePath) {
  return stripBom(await readFile(filePath, "utf-8"));
}

function stripBom(content) {
  return content.charCodeAt(0) === 0xfeff ? content.slice(1) : content;
}

function normalizeSplit(value) {
  return text(value).toLowerCase() || "unknown";
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

function numberArg(value, fallback) {
  if (value === undefined || value === null || value === "") {
    return fallback;
  }
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    throw new Error(`Invalid numeric argument: ${value}`);
  }
  return parsed;
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
