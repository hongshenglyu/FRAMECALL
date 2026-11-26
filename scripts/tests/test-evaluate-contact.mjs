import { readFileSync } from "node:fs";
import test from "node:test";
import assert from "node:assert/strict";
import { evaluateContact, convertContactCandidates } from "../evaluate-contact.mjs";

const fixture = () => JSON.parse(readFileSync(new URL("../../docs/examples/contact-evaluation.synthetic.json", import.meta.url), "utf8"));
const point = sourcePts => ({ segmentId: "S1", partId: "SYNTH-P1", sourcePts });
const candidate = (id, time, extra) => ({ id, eventType: "CONTACT", peak: point(time), ...extra });
const truth = (id, time) => ({
  id, sceneType: "CONTEST", visibility: "VISIBLE_CONTACT",
  start: point(time - 200), firstContact: point(time), end: point(time + 200)
});

test("an exact tolerance boundary matches; overlapping clips cannot rescue an out-of-tolerance peak", () => {
  const input = fixture();
  const [match] = input.matches;
  match.clips = [{ id: "CLIP", mediaRef: "synthetic", start: point(3000), end: point(7000) }];
  match.predictions = [candidate("A", 6000, { playableClipId: "CLIP" })];
  assert.equal(evaluateContact(input).matches[0].predictions[0].errorMs, 2000);
  match.predictions[0].peak = point(6001);
  const { micro } = evaluateContact(input);
  assert.deepEqual([micro.tp, micro.fp, micro.fn], [0, 1, 1]);
});

test("adjacent contests maximize match count first and assign duplicates by least timing error", () => {
  const input = fixture();
  const [match] = input.matches;
  match.truth = [truth("A", 4000), truth("B", 7000)];
  match.predictions = [candidate("C", 2100), candidate("D", 5500)];
  assert.deepEqual(evaluateContact(input).matches[0].truth.map(t => t.candidateId), ["C", "D"]);
  match.truth = [truth("A", 4000)];
  match.predictions = [candidate("C", 2100), candidate("D", 5500), candidate("E", 5500)];
  const result = evaluateContact(input);
  assert.equal(result.matches[0].truth[0].candidateId, "D");
  assert.equal(result.matches[0].predictions.find(p => p.id === "E").outcome, "FP");
  assert.deepEqual([result.micro.tp, result.micro.fp, result.micro.fn], [1, 2, 0]);
});

test("different matches and distinct continuous segments cannot share a TP", () => {
  const input = fixture();
  const [match] = input.matches;
  match.truth = [truth("A", 9500)];
  match.segments[0].sourceEndPts = 10000;
  match.segments.push({ ...match.segments[0], partId: "P2", segmentId: "S2", continuityId: "C2", matchStartMs: 9000 });
  match.predictions = [candidate("A", 1010, { peak: { ...point(1010), segmentId: "S2", partId: "P2" } })];
  input.matches.push({ ...fixture().matches[0], matchId: "M2", truth: [], predictions: [candidate("B", 9500)] });
  const gap = evaluateContact(input);
  assert.deepEqual([gap.micro.tp, gap.micro.fp, gap.micro.fn], [0, 2, 1]);
  match.segments[1].continuityId = "C1";
  assert.equal(evaluateContact(input).matches[0].metrics.tp, 1);
  match.predictions[0].peak.partId = "SYNTH-P1";
  assert.throws(() => evaluateContact(input), /Missing source PTS mapping/);
});

test("negative contests count as FP; insufficient view, scope, invalid video and unlabeled peaks stay distinct", () => {
  const input = fixture();
  const [match] = input.matches;
  match.truth = ["NO_VISIBLE_CONTACT", "INSUFFICIENT_VIEW", "OUT_OF_SCOPE"].map((visibility, i) =>
    ({ ...truth("T" + i, 3000 + i * 1000), visibility, firstContact: null }));
  match.segments[0].invalidRanges = [[6000, 6500]];
  match.predictions = [3000, 4000, 5000, 6200, 10500].map((time, i) => candidate("P" + i, time));
  const { micro } = evaluateContact(input);
  assert.deepEqual({ ...micro.predictionOutcomes },
    { FP: 1, INSUFFICIENT_VIEW: 1, OUT_OF_SCOPE: 1, INVALID_VIDEO: 1, UNANNOTATED: 1 });
  assert.deepEqual([micro.precision, micro.recall, micro.f1], [0, null, 0]);
  match.predictions = [];
  const empty = evaluateContact(input).micro;
  assert.deepEqual([empty.precision, empty.recall, empty.f1], [null, null, null]);
});

test("adjudicated inputs require protocol, independent review, run coverage and valid source mapping", () => {
  const input = fixture();
  const [match] = input.matches;
  input.kind = "ADJUDICATED";
  assert.throws(() => evaluateContact(input), /frozen protocol/);
  input.protocol.frozenRef = "fixture-protocol";
  match.coverageReviewRef = "fixture-coverage";
  match.predictionRunRef = "fixture-run";
  assert.throws(() => evaluateContact(input), /Annotated segment needs prediction run/);
  match.segments[0].predictionRunRef = match.predictionRunRef;
  assert.throws(() => evaluateContact(input), /independent adjudication/);
  match.truth[0].decision = {
    source: "LICENSED_REFEREE_ADJUDICATION", annotatorId: "A", reviewerId: "A", recordRef: "fixture"
  };
  assert.throws(() => evaluateContact(input), /independent adjudication/);
  match.truth[0].decision.reviewerId = "B";
  delete match.segments[0].timeBase;
  assert.throws(() => evaluateContact(input), /Invalid source PTS mapping/);
});

test("P3 conversion preserves original peak PTS and rejects unconfirmed upload scopes", () => {
  const run = { matchId: "M", partId: "P", segmentId: 0, sourceTimeBase: "1/90000", peakSourcePts: 90001, eventId: "X" };
  const scope = { matchId: "M", partId: "P", segmentId: "S", sourceSegmentId: 0, timeBase: "1/90000" };
  assert.equal(convertContactCandidates([run], scope)[0].peak.sourcePts, 90001);
  assert.throws(() => convertContactCandidates([{ ...run, matchId: "UPLOAD-T161" }], scope), /does not match/);
});
