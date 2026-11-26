const el = id => document.getElementById(id);
const video = el("video");
const roles = { INITIAL: "人工初标", REFEREE: "独立裁判记录", FUNCTION_TEST: "功能验证（与真实标注隔离）" };
const labels = { start: "争抢开始", firstContact: "首次可见接触", end: "争抢结束" };
const seconds = point => point ? `${point.partId.slice(-4)} ${(point.sourcePts * point.secondsPerTick).toFixed(3)} 秒` : "未记录";
let match, part, map, frameIndex, state, form, coverageStart, mediaReady;

function status(message, error = false) {
  el("status").textContent = message;
  el("status").className = error ? "error" : "";
}

async function api(path, options) {
  const response = await fetch(path, options);
  const data = await response.json();
  if (!response.ok) throw Error(data.error);
  return data;
}

function listen(id, type, callback) {
  el(id).addEventListener(type, async () => {
    try { await callback(); } catch (error) { status(error.message, true); }
  });
}

function choices(select, entries) {
  select.replaceChildren(...entries.map(([name, id]) => new Option(name, id)));
}

const config = await api("/matches");
el("profile").textContent = `${config.profile} · ${roles[config.role]}`;
el("reference-row").hidden = config.role !== "REFEREE";
choices(el("match"), config.matches.map(m => [m.matchLabel, m.matchId]));

function draw() {
  for (const [field, label] of Object.entries(labels)) {
    el(field + "-time").textContent = `${label} · ${seconds(form[field])}`;
  }
  el("coverage").textContent = `待保存起点 ${seconds(coverageStart)} · 已保存 ${state.coverage.length} 个区间`;
  const latest = new Map(state.events.map(event => [event.id, event]));
  el("events").replaceChildren(...[...latest.values()].map(event => {
    const button = document.createElement("button");
    button.textContent = `${seconds(event.start)} · ${event.visibility} · 修订 ${event.revision}`;
    button.onclick = async () => {
      try {
        const id = event.start.partId;
        if (id === part.partId) seek(map.pts.indexOf(event.start.sourcePts));
        else await openPart(id, event.start);
        setForm(event);
        status(`正在修订 ${event.id}`);
      } catch (error) { status(error.message, true); }
    };
    return button;
  }));
}

function setForm(event = {}) {
  form = { ...event };
  for (const [id, value] of Object.entries({ visibility: form.visibility, scene: form.sceneType || "CONTEST",
    note: form.note, reference: form.reference })) el(id).value = value ?? "";
  el("observed").checked = false;
  draw();
}

function capture() {
  if (frameIndex == null || video.seeking || !mediaReady) throw Error("请等待当前源帧显示");
  video.pause();
  return { partId: part.partId, segmentId: part.partId, sourcePts: map.pts[frameIndex],
    timeBase: map.timeBase, secondsPerTick: map.secondsPerTick };
}

function seek(index) {
  if (!Number.isInteger(map.pts[index])) throw Error("记录的源帧已不在当前区间");
  video.pause();
  if (index === frameIndex) return;
  frameIndex = null;
  video.currentTime = Math.ceil(map.pts[index] * map.secondsPerTick * 1000) / 1000;
}

function onFrame(_, { mediaTime }) {
  if (map && mediaReady) {
    const target = mediaTime / map.secondsPerTick;
    let a = 0, b = map.pts.length;
    while (a < b) {
      const middle = (a + b) >>> 1;
      if (map.pts[middle] < target) a = middle + 1;
      else b = middle;
    }
    const nearest = a === 0 || a < map.pts.length && map.pts[a] - target < target - map.pts[a - 1] ? a : a - 1;
    frameIndex = Math.abs(map.pts[nearest] * map.secondsPerTick - mediaTime) < .002 ? nearest : null;
    el("position").textContent = frameIndex === null ? "正在定位源帧" : `${mediaTime.toFixed(3)} 秒 · PTS ${map.pts[frameIndex]}`;
    if (target >= map.sourceEndPts) {
      video.pause();
      if (!video.seeking) seek(map.pts.length - 1);
    }
  }
  video.requestVideoFrameCallback(onFrame);
}
video.requestVideoFrameCallback(onFrame);

async function openPart(id, resume) {
  video.pause();
  mediaReady = false;
  frameIndex = null;
  coverageStart = null;
  part = match.parts.find(p => p.partId === id);
  el("part").value = id;
  status("读取原片 PTS…");
  map = await api("/frames/" + id);
  const ready = new Promise((resolve, reject) => {
    video.onloadedmetadata = resolve;
    video.onerror = () => reject(Error("原视频无法播放"));
  });
  video.src = "/media/" + id;
  await ready;
  mediaReady = true;
  video.playbackRate = Number(el("rate").value);
  const index = resume?.partId === id ? map.pts.indexOf(resume.sourcePts) : 0;
  seek(Math.max(index, 0));
  draw();
  status("原片已打开");
}

async function openMatch() {
  match = config.matches.find(m => m.matchId === el("match").value);
  state = await api("/state/" + match.matchId);
  choices(el("part"), match.parts.map(p => [p.partId.slice(-4), p.partId]));
  el("export").href = "/export/" + match.matchId;
  setForm(state.draft ?? {});
  await openPart(state.resume?.partId ?? match.parts[0].partId, state.resume);
}

for (const [field, label] of Object.entries(labels)) {
  const row = document.createElement("div");
  const mark = document.createElement("button");
  const clear = document.createElement("button");
  const time = document.createElement("output");
  mark.id = field + "-mark"; clear.id = field + "-clear"; time.id = field + "-time";
  mark.textContent = "记录" + label; clear.textContent = "清空";
  row.append(mark, clear, time);
  el("marks").append(row);
  listen(mark.id, "click", () => { form[field] = capture(); draw(); });
  listen(clear.id, "click", () => { delete form[field]; draw(); });
}

function fields() {
  return { ...form, visibility: el("visibility").value, sceneType: el("scene").value,
    note: el("note").value, reference: el("reference").value };
}

async function save(changes, message) {
  const resume = capture();
  state = await api("/state/" + match.matchId, { method: "PUT", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ...state, ...changes, resume }) });
  draw();
  status(message);
}

listen("coverage-start", "click", () => { coverageStart = capture(); draw(); });
listen("coverage-save", "click", async () => {
  const end = capture();
  if (!coverageStart || end.partId !== coverageStart.partId || end.sourcePts < coverageStart.sourcePts) {
    throw Error("请在同一分段标记已连续检查的起点和终点");
  }
  const range = [coverageStart.sourcePts, map.pts[frameIndex + 1] ?? map.sourceEndPts];
  await save({ coverage: [...state.coverage, { partId: end.partId, timeBase: map.timeBase, range }], draft: fields() }, "已保存检查区间");
  coverageStart = null;
  draw();
});
listen("draft", "click", () => save({ draft: fields() }, "已保存草稿与进度"));
listen("save", "click", async () => {
  const event = fields();
  const { start, end, firstContact, sceneType, visibility } = event;
  const partOrder = point => match.parts.findIndex(p => p.partId === point.partId);
  const before = (a, b) => partOrder(a) < partOrder(b) || a.partId === b.partId && a.sourcePts < b.sourcePts;
  const group = point => match.parts[partOrder(point)].continuityId;
  if (!el("observed").checked || !visibility || !start || !end || !before(start, end) || group(start) !== group(end)) {
    throw Error("请人工确认，并记录同一连续区间内的争抢起止");
  }
  if (visibility === "VISIBLE_CONTACT" ? sceneType !== "CONTEST" || !firstContact ||
    before(firstContact, start) || !before(firstContact, end) : firstContact != null) {
    throw Error("可见接触须在争抢范围内，其余结论请清空首次可见接触");
  }
  const record = { ...event, id: event.id || crypto.randomUUID(), revision: (event.revision ?? 0) + 1,
    savedAt: new Date().toISOString(), profile: config.profile, role: config.role };
  await save({ events: [...state.events, record], draft: null }, "事件已保存");
  setForm();
});
listen("new", "click", () => setForm());
listen("match", "change", openMatch);
listen("part", "change", () => openPart(el("part").value));
listen("next", "click", () => {
  const next = match.parts[match.parts.indexOf(part) + 1];
  if (!next) throw Error("本场已到最后一段");
  return openPart(next.partId);
});
listen("resume", "click", () => state.resume && openPart(state.resume.partId, state.resume));
listen("rate", "change", () => { video.playbackRate = Number(el("rate").value); });
for (const [button, delta] of [["previous-frame", -1], ["next-frame", 1]]) {
  listen(button, "click", () => {
    capture();
    seek(Math.min(map.pts.length - 1, Math.max(0, frameIndex + delta)));
  });
}
try { await openMatch(); } catch (error) { status(error.message, true); }
