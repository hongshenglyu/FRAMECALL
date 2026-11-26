import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

function check(condition, message) {
  if (!condition) throw new Error(message);
}

const text = value => typeof value === "string" && value.trim().length > 0;
const ratio = (n, d) => d ? n / d : null;
const order = (a, b) => a.timeMs - b.timeMs || (a.id < b.id ? -1 : Number(a.id > b.id));
const inside = (ranges, t) => ranges.some(([start, end]) => start <= t && t < end);

// In one dimension, uncrossing two eligible edges preserves eligibility
// and cannot increase absolute timing error. Optimize over sorted prefixes.
function matchEvents(truth, predictions, toleranceMs) {
  const n = predictions.length;
  const trace = truth.map(() => new Uint8Array(n + 1));
  let counts = new Uint32Array(n + 1);
  let costs = new Float64Array(n + 1);
  for (const [i, event] of truth.entries()) {
    const nextCounts = new Uint32Array(n + 1);
    const nextCosts = new Float64Array(n + 1);
    for (let j = 1; j <= n; j++) {
      let count = counts[j];
      let cost = costs[j];
      let step = 0;
      function consider(matches, error, direction) {
        if (matches > count || matches === count && error < cost) {
          count = matches;
          cost = error;
          step = direction;
        }
      }
      consider(nextCounts[j - 1], nextCosts[j - 1], 1);
      const error = Math.abs(event.timeMs - predictions[j - 1].timeMs);
      if (error <= toleranceMs) consider(counts[j - 1] + 1, costs[j - 1] + error, 2);
      nextCounts[j] = count;
      nextCosts[j] = cost;
      trace[i][j] = step;
    }
    counts = nextCounts;
    costs = nextCosts;
  }

  const matches = [];
  let i = truth.length - 1;
  let j = n;
  while (i >= 0 && j > 0) {
    const step = trace[i][j];
    if (step === 2) matches.push([truth[i], predictions[j - 1]]);
    if (step !== 1) i--;
    if (step !== 0) j--;
  }
  return matches.reverse();
}

function unique(rows, key, name) {
  const values = new Map();
  for (const row of rows) {
    const value = key(row);
    check(text(value) && !values.has(value), `Missing or duplicate ${name}: ${value}`);
    values.set(value, row);
  }
  return values;
}

function evaluateMatch(match, toleranceMs, formal) {
  const { matchId } = match;
  check(["DEVELOPMENT", "VALIDATION", "TEST"].includes(match.split), `Invalid split for ${matchId}`);
  check(!formal || text(match.coverageReviewRef) && text(match.predictionRunRef),
    `Reviewed annotation coverage and completed prediction run are required for ${matchId}`);
  check(match.segments.length > 0, `Missing source PTS mapping for ${matchId}`);
  const segments = unique(match.segments, s => s.segmentId, "segmentId");
  const groups = new Map();
  for (const segment of segments.values()) {
    const { partId, timeBase, sourceStartPts, sourceEndPts, matchStartMs, continuityId } = segment;
    check(!formal || !segment.annotatedRanges.length || text(segment.predictionRunRef),
      `Annotated segment needs prediction run evidence: ${segment.segmentId}`);
    const base = typeof timeBase === "string" && /^(\d+)\/(\d+)$/.exec(timeBase);
    check(text(partId) && text(continuityId) && base && Number(base[1]) > 0 && Number(base[2]) > 0
      && Number.isSafeInteger(sourceStartPts) && Number.isSafeInteger(sourceEndPts)
      && sourceEndPts > sourceStartPts && Number.isFinite(matchStartMs), `Invalid source PTS mapping: ${segment.segmentId}`);
    const toMs = pts => matchStartMs + (pts - sourceStartPts) * 1000 * Number(base[1]) / Number(base[2]);
    function ranges(rows) {
      return rows.map(([start, end]) => {
        check(Number.isSafeInteger(start) && Number.isSafeInteger(end)
          && sourceStartPts <= start && start < end && end <= sourceEndPts, `Invalid source range: ${segment.segmentId}`);
        return [toMs(start), toMs(end)];
      });
    }
    const group = groups.get(continuityId) ?? { truth: [], candidates: [], ignored: [], spans: [], covered: [], invalid: [] };
    group.spans.push([matchStartMs, toMs(sourceEndPts)]);
    group.covered.push(...ranges(segment.annotatedRanges));
    group.invalid.push(...ranges(segment.invalidRanges ?? []));
    groups.set(continuityId, group);
    segments.set(segment.segmentId, { ...segment, toMs, group });
  }
  const spans = [...groups.values()].flatMap(group => group.spans).sort((a, b) => a[0] - b[0]);
  check(spans.every(([start], i) => !i || start >= spans[i - 1][1] - 1e-7), `Overlapping match mappings: ${matchId}`);
  for (const group of groups.values()) {
    group.spans.sort((a, b) => a[0] - b[0]);
    check(group.spans.every(([start], i) => !i || Math.abs(start - group.spans[i - 1][1]) < 1e-7),
      `Discontinuous mapping shares a continuityId in ${matchId}`);
    const merged = [];
    for (const [start, end] of group.covered.sort((a, b) => a[0] - b[0])) {
      const last = merged.at(-1);
      if (last && start <= last[1] + 1e-7) last[1] = Math.max(last[1], end);
      else merged.push([start, end]);
    }
    group.covered = merged;
  }

  function point(value, allowEnd = false) {
    const segment = segments.get(value?.segmentId);
    const pts = value?.sourcePts;
    check(segment && value.partId === segment.partId && Number.isSafeInteger(pts)
      && pts >= segment.sourceStartPts && (pts < segment.sourceEndPts || allowEnd && pts === segment.sourceEndPts),
    `Missing source PTS mapping or point outside mapped segment: ${matchId} ${JSON.stringify(value)}`);
    return { timeMs: segment.toMs(pts), group: segment.group, continuityId: segment.continuityId };
  }

  const clips = unique(match.clips ?? [], clip => clip.id, "clip ID");
  for (const clip of clips.values()) {
    const start = point(clip.start);
    const end = point(clip.end, true);
    check(text(clip.mediaRef) && start.group === end.group && start.timeMs < end.timeMs,
      `Invalid delivered clip: ${clip.id}`);
    clips.set(clip.id, { ...clip, startMs: start.timeMs, endMs: end.timeMs, continuityId: start.continuityId });
  }

  const truthDetails = [];
  for (const event of unique(match.truth, e => e.id, "truth ID").values()) {
    const { visibility, sceneType, decision } = event;
    check(["VISIBLE_CONTACT", "NO_VISIBLE_CONTACT", "INSUFFICIENT_VIEW", "OUT_OF_SCOPE"].includes(visibility),
      `Unknown truth visibility: ${event.id}`);
    check(text(sceneType) && (!formal || decision?.source === "LICENSED_REFEREE_ADJUDICATION"
      && text(decision.annotatorId) && text(decision.reviewerId)
      && decision.annotatorId !== decision.reviewerId && text(decision.recordRef)), `Missing independent adjudication: ${event.id}`);
    const start = point(event.start);
    const end = point(event.end, true);
    const { group } = start;
    check(group === end.group && start.timeMs < end.timeMs
      && group.covered.some(([a, b]) => a <= start.timeMs && end.timeMs <= b)
      && !group.invalid.some(([a, b]) => start.timeMs < b && end.timeMs > a),
    `Truth must be within one fully annotated, valid continuous interval: ${event.id}`);
    const detail = { ...event, startMs: start.timeMs, endMs: end.timeMs, outcome: visibility };
    truthDetails.push(detail);
    if (visibility === "VISIBLE_CONTACT") {
      const first = point(event.firstContact);
      check(sceneType === "CONTEST" && first.group === group && start.timeMs <= first.timeMs && first.timeMs < end.timeMs,
        `Positive truth requires visible contact within a contest: ${event.id}`);
      group.truth.push({ id: event.id, timeMs: first.timeMs, detail });
      detail.firstContactMs = first.timeMs;
      detail.outcome = "FN";
    } else {
      check(event.firstContact == null, `Unexpected first contact: ${event.id}`);
      if (visibility !== "NO_VISIBLE_CONTACT") group.ignored.push(detail);
    }
  }

  const predictionDetails = [];
  for (const prediction of unique(match.predictions, e => e.id, "candidate ID").values()) {
    check(prediction.eventType === "CONTACT", `Expected CONTACT candidate: ${prediction.id}`);
    const peak = point(prediction.peak);
    const { timeMs, group, continuityId } = peak;
    if (prediction.playableClipId != null) {
      const clip = clips.get(prediction.playableClipId);
      check(clip?.continuityId === continuityId && clip.startMs <= timeMs && timeMs < clip.endMs,
        `Delivered clip does not cover candidate peak: ${prediction.id}`);
    }
    const detail = { ...prediction, peakMs: timeMs, continuityId };
    predictionDetails.push(detail);
    if (inside(group.invalid, timeMs)) detail.outcome = "INVALID_VIDEO";
    else if (!inside(group.covered, timeMs)) detail.outcome = "UNANNOTATED";
    else group.candidates.push({ id: prediction.id, timeMs, detail });
  }
  for (const group of groups.values()) {
    group.truth.sort(order);
    group.candidates.sort(order);
    for (const [truth, candidate] of matchEvents(group.truth, group.candidates, toleranceMs)) {
      const errorMs = Math.abs(truth.timeMs - candidate.timeMs);
      Object.assign(truth.detail, { outcome: "TP", candidateId: candidate.id, errorMs });
      Object.assign(candidate.detail, { outcome: "TP", truthId: truth.id, errorMs });
    }
    for (const { detail, timeMs } of group.candidates) {
      detail.outcome ??= group.ignored.find(event => event.startMs <= timeMs && timeMs < event.endMs)?.visibility ?? "FP";
    }
  }
  return { matchId, split: match.split, metrics: metrics(truthDetails, predictionDetails),
    truth: truthDetails, predictions: predictionDetails, clips: [...clips.values()] };
}

function rates(tp, fp, fn) {
  return { tp, fp, fn, precision: ratio(tp, tp + fp), recall: ratio(tp, tp + fn), f1: ratio(2 * tp, 2 * tp + fp + fn) };
}

function metrics(truth, predictions) {
  const outcomes = Object.create(null);
  for (const { outcome } of predictions) outcomes[outcome] = (outcomes[outcome] ?? 0) + 1;
  return { ...rates(outcomes.TP ?? 0, outcomes.FP ?? 0, truth.filter(e => e.outcome === "FN").length), predictionOutcomes: outcomes };
}

export function evaluateContact(input) {
  check(input.schema === "framecall.contact-evaluation.v1", "Expected framecall.contact-evaluation.v1");
  check(["SYNTHETIC", "ADJUDICATED"].includes(input.kind), "Declare SYNTHETIC or ADJUDICATED input");
  const formal = input.kind === "ADJUDICATED";
  const toleranceMs = input.protocol?.toleranceMs ?? 2000;
  check(Number.isFinite(toleranceMs) && toleranceMs >= 0, "Invalid toleranceMs");
  check(!formal || text(input.protocol?.frozenRef) && Number.isFinite(input.protocol.toleranceMs),
    "Adjudicated evaluation requires a frozen protocol reference and explicit toleranceMs");
  const matches = [...unique(input.matches, m => m.matchId, "matchId").values()]
    .map(match => evaluateMatch(match, toleranceMs, formal));
  check(matches.length > 0, "No matches supplied");
  check(!formal || new Set(matches.map(m => m.split)).size === 1, "Evaluate one split per adjudicated report");
  return { kind: input.kind, protocol: { ...input.protocol, toleranceMs },
    micro: metrics(matches.flatMap(m => m.truth), matches.flatMap(m => m.predictions)),
    matches, reviewMedia: { status: "NOT_COMPUTED", scope: "DELIVERED_PLAYABLE_CLIPS_ONLY" } };
}

// Convert a P3 candidates.json method on original, identified footage.
// Upload-local scopes require an explicit external mapping before conversion.
export function convertContactCandidates(events, segment) {
  return events.map(event => {
    check(event.matchId === segment.matchId && event.partId === segment.partId
      && event.sourceTimeBase === segment.timeBase && event.segmentId === segment.sourceSegmentId
      && Number.isSafeInteger(event.peakSourcePts) && text(segment.segmentId),
    `Candidate source does not match original segment: ${event.eventId}`);
    return { id: event.eventId, eventType: "CONTACT",
      peak: { partId: event.partId, segmentId: segment.segmentId, sourcePts: event.peakSourcePts },
      playableClipId: null };
  });
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  try {
    check(process.argv.length === 3, "Usage: node scripts/evaluate-contact.mjs input.json");
    const input = JSON.parse((await readFile(process.argv[2], "utf8")).replace(/^\uFEFF/, ""));
    console.log(JSON.stringify(evaluateContact(input), null, 2));
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
