import { createServer } from "node:http";
import { createReadStream, existsSync, mkdirSync, readFileSync, renameSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { execFile } from "node:child_process";
import { promisify } from "node:util";

const [profile = "owner", role = "INITIAL"] = process.argv.slice(2);
if (!/^[\w-]+$/.test(profile) || !["INITIAL", "REFEREE", "FUNCTION_TEST"].includes(role)) {
  throw Error("用法：node scripts/annotate-matches.mjs 标注人代号 INITIAL|REFEREE|FUNCTION_TEST");
}
const port = Number(process.env.FRAMECALL_ANNOTATION_PORT || 8765);
const origin = `http://127.0.0.1:${port}`;
const roots = { E2026: process.env.FRAMECALL_E2026_ROOT || "F:/2026年吉林外国语大学足球联赛",
  E2025: process.env.FRAMECALL_E2025_ROOT || "F:/吉林外国语大学25赛季" };
const { matches } = JSON.parse(readFileSync("docs/data/full-match-annotation.json", "utf8"));
const directory = join("storage", "annotations", "full-match", role);
const indexDirectory = join("storage", "annotations", "full-match", "frame-index");
mkdirSync(directory, { recursive: true });
mkdirSync(indexDirectory, { recursive: true });
const parts = new Map(matches.flatMap(match => match.parts.map(part => [
  part.partId, { ...part, path: join(roots[match.eventId], part.relativePath) }
])));
const probes = new Map();

function sourceSize(part) {
  const size = statSync(part.path).size;
  if (size !== part.sizeBytes) throw Error(`原片大小与清单不符：${part.partId}`);
  return size;
}

async function frameMap(part) {
  const stamp = JSON.stringify([statSync(part.path).mtimeMs, sourceSize(part),
    part.startSeconds, part.endSeconds, part.trimEndPts]);
  const cached = probes.get(part.partId);
  if (cached?.stamp === stamp) return cached.promise;
  const indexPath = join(indexDirectory, part.partId + ".json");
  if (existsSync(indexPath)) {
    const index = JSON.parse(readFileSync(indexPath, "utf8"));
    if (index.stamp === stamp) {
      probes.set(part.partId, { stamp, promise: Promise.resolve(index.frames) });
      return index.frames;
    }
  }
  const promise = promisify(execFile)("ffprobe", ["-v", "error", "-select_streams", "v:0", "-show_packets",
    "-show_entries", "stream=time_base:packet=pts,duration", "-of", "json", part.path], { maxBuffer: 32 * 1024 * 1024 })
    .then(({ stdout }) => {
      const { packets, streams } = JSON.parse(stdout);
      const timeBase = streams[0].time_base;
      const [n, d] = timeBase.split("/").map(Number);
      const secondsPerTick = n / d;
      if (packets.some(p => !Number.isSafeInteger(p.pts))) throw Error("原片存在缺少 PTS 的视频包");
      packets.sort((a, b) => a.pts - b.pts);
      const rawFirst = packets[0].pts;
      const last = packets.at(-1);
      const sourceStartPts = packets.find(p => p.pts * secondsPerTick >= part.startSeconds).pts;
      const sourceEndPts = part.trimEndPts ?? (part.endSeconds === null ? undefined :
        packets.find(p => p.pts * secondsPerTick >= part.endSeconds)?.pts) ?? last.pts + last.duration;
      const pts = packets.map(p => p.pts).filter(p => p >= sourceStartPts && p < sourceEndPts);
      if (!Number.isSafeInteger(sourceEndPts) || pts.length < 2 || pts.some((p,i) => i && p <= pts[i-1])) {
        throw Error(`原片 PTS 区间无效：${part.partId}`);
      }
      const frames = { partId: part.partId, timeBase, secondsPerTick, rawFirst, sourceStartPts, sourceEndPts, pts };
      writeFileSync(indexPath, JSON.stringify({ stamp, frames }));
      return frames;
    }).catch(error => { probes.delete(part.partId); throw error; });
  probes.set(part.partId, { stamp, promise });
  return promise;
}

function video(request, response, part) {
  const size = sourceSize(part);
  const range = request.headers.range;
  const parsed = range && /^bytes=(\d+)-(\d*)$/.exec(range);
  const start = parsed ? Number(parsed[1]) : 0;
  const end = parsed?.[2] ? Math.min(Number(parsed[2]), size - 1) : size - 1;
  if (range && (!parsed || end < start)) {
    response.writeHead(416, { "Content-Range": `bytes */${size}` });
    return response.end();
  }
  const headers = { "Content-Type": "video/mp4", "Accept-Ranges": "bytes", "Content-Length": end - start + 1 };
  if (range) headers["Content-Range"] = `bytes ${start}-${end}/${size}`;
  response.writeHead(range ? 206 : 200, headers);
  if (request.method === "HEAD") return response.end();
  const input = createReadStream(part.path, { start, end });
  input.on("error", () => response.destroy());
  response.on("close", () => input.destroy());
  input.pipe(response);
}

createServer(async (request, response) => {
  const json = (body, status = 200) => {
    response.writeHead(status, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" });
    response.end(JSON.stringify(body));
  };
  try {
    const [resource, id] = new URL(request.url, origin).pathname.split("/").slice(1);
    if (resource === "media" && parts.has(id)) return video(request, response, parts.get(id));
    if (resource === "frames" && parts.has(id)) return json(await frameMap(parts.get(id)));
    if (resource === "matches") return json({ matches, role, profile });
    if (resource === "state" || resource === "export") {
      const match = matches.find(entry => entry.matchId === id);
      if (!match) return json({ error: "未找到比赛" }, 404);
      const file = join(directory, `${profile}-${id}.json`);
      const loadState = () => existsSync(file) ? JSON.parse(readFileSync(file, "utf8")) :
        { revision: 0, events: [], coverage: [], draft: null, resume: null };
      let state = loadState();
      if (resource === "state" && request.method === "PUT") {
        if (request.headers.origin !== origin) return json({ error: "请从本地标注页面保存" }, 403);
        const chunks = [];
        for await (const chunk of request) chunks.push(chunk);
        const next = JSON.parse(Buffer.concat(chunks));
        state = loadState();
        if (next.revision !== state.revision) return json({ error: "保存版本冲突，请重新打开比赛" }, 409);
        state = { ...next, revision: state.revision + 1 };
        writeFileSync(file + ".tmp", JSON.stringify(state, null, 2));
        renameSync(file + ".tmp", file);
      }
      if (resource === "state") return json(state);
      let matchStartMs = 0;
      const segments = [];
      for (const part of match.parts) {
        const { partId, timeBase, sourceStartPts, sourceEndPts, secondsPerTick } = await frameMap(parts.get(part.partId));
        segments.push({ partId, segmentId: partId, continuityId: part.continuityId, timeBase, sourceStartPts,
          sourceEndPts, matchStartMs, annotatedRanges: state.coverage.filter(r => r.partId === partId).map(r => r.range) });
        matchStartMs += (sourceEndPts - sourceStartPts) * secondsPerTick * 1000;
      }
      const latest = new Map(state.events.map(event => [event.id, event]));
      const split = match.proposedMatchSplit.replace("_RESERVED", "");
      response.setHeader("Content-Disposition", `attachment; filename="${id}-${profile}-${role}.json"`);
      return json({ schema: "framecall.full-match-annotations.v1", matchId: id, split, profile, role, segments,
        truth: role === "FUNCTION_TEST" ? [] : [...latest.values()], ...state });
    }
    const file = resource === "" ? "index.html" : resource === "annotation.mjs" ? resource : null;
    if (!file) return json({ error: "页面不存在" }, 404);
    response.writeHead(200, { "Content-Type": file.endsWith(".mjs") ? "text/javascript; charset=utf-8" : "text/html; charset=utf-8" });
    response.end(readFileSync(join("scripts", "match-annotation", file)));
  } catch (error) {
    json({ error: error.message }, 400);
  }
}).listen(port, "127.0.0.1", () => console.log(`整场标注 ${origin} · ${profile} / ${role}`));
