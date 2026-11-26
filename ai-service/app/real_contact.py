"""Upload adapter for the verified P1/P2 scripts and P3/P4 modules. No mock fallback."""
from pathlib import Path
from types import SimpleNamespace
import importlib.util
import json
import threading
import time

from .schemas import AnalysisResult, DisputeEventResult, ProgressUpdate
from .security import send_progress
from .contact_candidates import analyze_frames
from .contact_clips import plan_windows, extract_windows

ROOT = Path(__file__).resolve().parents[2]
GPU = threading.Lock()


class AnalysisCancelled(Exception):
    pass


def script(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), ROOT/'scripts'/f'{name}.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run_real_contact(request, cancel_checker):
    def check():
        if cancel_checker(request.task_id):
            raise AnalysisCancelled()
    while not GPU.acquire(timeout=0.2):
        check()
    try:
        check()
        return execute(request, check)
    finally:
        GPU.release()


def plan_review_windows(events, tracks, config):
    by_segment = {}
    for event in events:
        by_segment.setdefault(event['segmentId'], []).append(event)
    bounds = {}
    for frame in tracks:
        segment = frame['segmentId']
        start, _ = bounds.get(segment, (frame['timeMs'], None))
        bounds[segment] = start, frame['timeMs']
    windows = []
    for segment, candidates in by_segment.items():
        planned = plan_windows(candidates, *bounds[segment], **config)
        if len(by_segment) > 1:
            for window in planned:
                window['clipId'] = f"segment-{segment}-{window['clipId']}"
        windows.extend(planned)
    return windows


def execute(request, check):
    import av
    import yaml
    out = Path(request.output_dir)
    out.mkdir(parents=True, exist_ok=True)
    started = time.perf_counter()
    stages = []
    def progress(stage, percent):
        check()
        stages.append(dict(stage=stage, elapsedSeconds=time.perf_counter()-started))
        send_progress(request, ProgressUpdate(taskId=request.task_id, stage=stage, progressPercent=percent, message=stage))
    def write(name, value):
        (out/name).write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False), encoding='utf8')
    progress('EXTRACTING', 8)
    with av.open(request.video_path) as container:
        stream = container.streams.video[0]
        duration = float(stream.duration*stream.time_base) if stream.duration is not None else float('inf')
    progress('DETECTING', 15)
    detection = script('real-yolo-smoke').run(SimpleNamespace(video_path=request.video_path, task_id=request.task_id,
        start_seconds=0, duration_seconds=duration, imgsz=640, conf=0.10, check_cancel=check), out)
    write('detection-run.json', detection)
    rows = [json.loads(line) for line in (out/'detections.jsonl').read_text(encoding='utf8').splitlines()]
    progress('TRACKING', 50)
    config = yaml.safe_load((ROOT/'config/bytetrack-p2.yaml').read_text(encoding='utf8'))
    tracks, tracking = script('real-bytetrack').associate(rows, config, 1.0, check_cancel=check)
    with (out/'tracks.jsonl').open('w', encoding='utf8') as file:
        for row in tracks:
            file.write(json.dumps(row, ensure_ascii=False)+'\n')
    write('tracking-run.json', tracking)
    progress('EVENT_ANALYSIS', 68)
    candidates = analyze_frames(tracks, json.loads((ROOT/'config/contact-candidates.json').read_text(encoding='utf8')), emit=lambda _:check())
    write('candidates.json', candidates)
    events = candidates['methods']['combined']
    first, last = rows[0]['timeMs'], rows[-1]['timeMs']
    source = dict(matchId=None, partId=None, localScopeId=f'UPLOAD-T{request.task_id}', identityStatus='UNCONFIRMED_UPLOAD',
                  relativePath=Path(request.video_path).name, timeline='UPLOADED_FILE_PTS',
                  sampleStartTimeMs=first, sampleEndTimeMs=last)
    progress('CLIPPING', 82)
    windows = plan_review_windows(events, tracks, json.loads((ROOT/'config/contact-clips.json').read_text(encoding='utf8'))) if events else []
    if windows:
        extract_windows(request.video_path, windows, events, out, check_cancel=check)
    results = []
    for event in events:
        clip = next((w for w in windows if event['eventId'] in w['eventIds']), None)
        peak = event['peakEvidence']
        metrics = [dict(name='min_distance', value=event['minNormalizedDistance'], unit='框高', description='最小归一化距离')]
        for name, field, reason in [('closing_speed','closingSpeedHeightPerSecond','closingUnavailableReason'),
                                    ('relative_velocity_delta','relativeVelocityChangeHeightPerSecond','velocityChangeUnavailableReason')]:
            metrics.append(dict(name=name, value=peak[field], unit='框高/秒', description='局部图像平面运动证据', unavailableReason=peak[reason]))
        metadata = dict(source=source, candidate=event, clip=clip,
                        clipPeakTimeMs=event['peakTimeMs']-clip['firstSourceTimeMs'] if clip else None)
        results.append(DisputeEventResult(externalId=event['eventId'], eventType='CONTACT', riskLevel='UNCERTAIN',
            timestampMs=round(event['peakTimeMs']), windowStartMs=round(event['startTimeMs']), windowEndMs=round(event['endTimeMs']),
            involvedTrackIds=[int(p['trackKey'].rsplit(':T',1)[1]) for p in event['trackParticipants']],
            summary='真实接触候选，需人工复核；非犯规概率或自动判罚', evidenceMetrics=metrics, trackBoxes=[],
            keyframePath=str(out/f"{event['eventId']}.jpg") if clip else '', clipPath=str(out/clip['file']) if clip else '',
            confidence=event['candidateScore'], contactMetadata=metadata))
    check()
    metrics = dict(mode='REAL_CONTACT', mockFallback=False, source=source, detection=detection,
                   tracking=tracking, candidateStatistics=candidates['statistics'], originalCandidates=len(events),
                   clips=len(windows), boundCandidates=sum(bool(e.clip_path) for e in results),
                   elapsedSeconds=time.perf_counter()-started, stages=stages)
    result = AnalysisResult(taskId=request.task_id, status='SUCCEEDED', stage='COMPLETED', progressPercent=100,
        resultJsonPath=str(out/'analysis_result.json'), events=results, metrics=metrics)
    (out/'analysis_result.json').write_text(result.model_dump_json(by_alias=True, indent=2), encoding='utf8')
    progress('COMPLETED', 100)
    return result
