"""Associate saved real detections with Ultralytics BYTETracker; never run YOLO."""
from __future__ import annotations

import argparse
from collections import defaultdict
from fractions import Fraction
import hashlib
import importlib.metadata
import inspect
import json
import os
from pathlib import Path
from statistics import median
import sys
import time
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]


def arguments():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--detections', type=Path, default=ROOT/'artifacts/real-yolo-smoke/p1-extended-60s/detections.jsonl')
    p.add_argument('--event-root', type=Path, required=True)
    p.add_argument('--config', type=Path, default=ROOT/'config/bytetrack-p2.yaml')
    p.add_argument('--run-name', required=True)
    p.add_argument('--max-gap-seconds', type=float, default=1.0)
    a = p.parse_args()
    if Path(a.run_name).name != a.run_name or a.run_name in {'.', '..'} or a.max_gap_seconds <= 0:
        p.error('Use a single new directory name and positive gap limit')
    return a


def associate(rows, config, max_gap_seconds):
    import numpy as np
    from ultralytics.engine.results import Boxes
    from ultralytics.trackers.byte_tracker import BYTETracker

    if len(rows) < 2:
        raise ValueError('At least two real frames are required')
    identities = {(r['matchId'], r['partId']) for r in rows}
    if len(identities) != 1:
        raise ValueError('One match/part per invocation; new invocations reset tracking')
    rate = (len(rows)-1)*1000/(rows[-1]['timeMs']-rows[0]['timeMs'])
    tracker = BYTETracker(SimpleNamespace(**config), frame_rate=rate)
    outputs, observations = [], defaultdict(list)
    segment = 0
    resets = []
    previous = None
    low_candidates = 0
    started = time.perf_counter()
    for i, row in enumerate(rows):
        if previous is not None:
            delta = row['timeMs']-previous['timeMs']
            if delta <= 0 or row['frameIndex'] <= previous['frameIndex']:
                raise ValueError('Non-monotonic source frame order or PTS')
            if row['frameIndex'] != previous['frameIndex']+1 or delta > max_gap_seconds*1000:
                tracker.reset()
                segment += 1
                resets.append({'inputFrame': i, 'segmentId': segment, 'reason': 'FRAME_OR_PTS_GAP'})
        detections = row['detections']
        data = np.asarray([[b[k] for k in ['x1','y1','x2','y2','confidence','classId']]
                           for b in detections], dtype=np.float32).reshape(-1, 6)
        scores = data[:, 4]
        high = np.flatnonzero(scores >= config['track_high_thresh'])
        low = np.flatnonzero((scores > config['track_low_thresh']) & (scores < config['track_high_thresh']))
        low_candidates += len(low)
        result = tracker.update(Boxes(data, orig_shape=(row['height'], row['width'])))
        tracks = []
        for box in result:
            x1, y1, x2, y2, tid, score, cls, relative_index = box.tolist()
            # The pinned implementation indexes the HIGH or LOW subset separately.
            pool = high if score >= config['track_high_thresh'] else low
            detection_index = int(pool[int(relative_index)])
            original = detections[detection_index]
            if abs(score-original['confidence']) > 0.000001 or int(cls) != original['classId']:
                raise RuntimeError('Tracker/detection association index mismatch')
            tid = int(tid)
            track = {'trackId': tid, 'trackKey': f"{row['matchId']}:{row['partId']}:S{segment}:T{tid}",
                     'confidence': score, 'classId': int(cls), 'x1': x1, 'y1': y1, 'x2': x2, 'y2': y2,
                     'detectionIndex': detection_index,
                     'detectionBbox': [original[k] for k in ['x1','y1','x2','y2']]}
            tracks.append(track)
            observations[(segment, tid)].append((i, row['timeMs']))
        if len({t['trackId'] for t in tracks}) != len(tracks):
            raise RuntimeError('Duplicate active ID in one frame')
        frame = {k: v for k, v in row.items() if k != 'detections'}
        frame.update(segmentId=segment, tracks=tracks)
        outputs.append(frame)
        previous = row
    association_seconds = time.perf_counter()-started
    summaries = []
    for (seg, tid), points in observations.items():
        longest = current = 1
        reappearances = []
        for (pi, pt), (ni, nt) in zip(points, points[1:]):
            if ni == pi+1:
                current += 1
                longest = max(longest, current)
            else:
                current = 1
                reappearances.append({'beforeClipFrame': pi, 'afterClipFrame': ni,
                                      'missingInputFrames': ni-pi-1, 'elapsedMs': nt-pt})
        summaries.append({'segmentId': seg, 'trackId': tid, 'observedFrames': len(points),
                          'firstClipFrame': points[0][0], 'lastClipFrame': points[-1][0],
                          'firstTimeMs': points[0][1], 'lastTimeMs': points[-1][1],
                          'spanSeconds': (points[-1][1]-points[0][1])/1000,
                          'longestConsecutiveFrames': longest, 'reappearances': reappearances})
    implementation = Path(inspect.getfile(BYTETracker))
    stats = {'implementation': 'ultralytics.trackers.byte_tracker.BYTETracker',
             'implementationSha256': hashlib.sha256(implementation.read_bytes()).hexdigest(),
             'frameRateForBufferScaling': rate, 'maxTimeLostFrames': tracker.max_time_lost,
             'associationSeconds': association_seconds, 'resetEvents': resets,
             'lowScoreCandidateObservations': low_candidates,
             'tracks': sorted(summaries, key=lambda s: (s['segmentId'], s['trackId']))}
    return outputs, stats


def render(frames, source, out):
    import av
    import cv2

    cv2.setNumThreads(2)
    tb = Fraction(1, 90000)
    first = Fraction(frames[0]['sourcePts'])*Fraction(frames[0]['sourceTimeBase'])
    count = 0
    with av.open(str(source), 'r') as original, av.open(str(out/'tracked.mp4'), 'w') as output:
        stream = original.streams.video[0]
        stream.thread_count = 2
        original.seek(int(first/stream.time_base), stream=stream)
        encoder = output.add_stream('libx264', rate=max(1, round(float(stream.average_rate))))
        encoder.width, encoder.height = stream.codec_context.width, stream.codec_context.height
        encoder.pix_fmt = 'yuv420p'
        encoder.time_base = encoder.codec_context.time_base = tb
        encoder.codec_context.thread_count = 2
        encoder.options = {'preset':'ultrafast','crf':'23','tune':'zerolatency'}
        for image_frame in original.decode(stream):
            source_time = image_frame.pts*image_frame.time_base
            if source_time < first:
                continue
            if count == len(frames):
                break
            row = frames[count]
            if source_time != Fraction(row['sourcePts'])*Fraction(row['sourceTimeBase']):
                raise RuntimeError('Decoded source frame does not match detection PTS')
            image = image_frame.to_ndarray(format='bgr24')
            for t in row['tracks']:
                tid = t['trackId']
                color = (int((tid*47)%190+50), int((tid*89)%190+50), int((tid*131)%190+50))
                x1,y1,x2,y2 = (round(t[k]) for k in ['x1','y1','x2','y2'])
                cv2.rectangle(image, (x1,y1), (x2,y2), color, 2)
                cv2.putText(image, str(tid), (max(0,x1),max(15,y1-4)), cv2.FONT_HERSHEY_SIMPLEX, 0.55, color, 2)
            cv2.putText(image, f"ByteTrack | frame {row['frameIndex']} | {row['timeMs']/1000:.3f}s | S{row['segmentId']}",
                        (20,34), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0,255,255), 2)
            frame = av.VideoFrame.from_ndarray(image, format='bgr24')
            frame.pts, frame.time_base = round((source_time-first)/tb), tb
            for packet in encoder.encode(frame):
                output.mux(packet)
            if count in {0,len(frames)//4,len(frames)//2,3*len(frames)//4,len(frames)-1}:
                cv2.imwrite(str(out/f'frame-{count:04d}.jpg'), image)
            count += 1
            if count%300 == 0:
                print('RENDERED', count, flush=True)
        for packet in encoder.encode():
            output.mux(packet)
    if count != len(frames):
        raise RuntimeError('Source ended before all detection frames were rendered')


def run(a, out):
    import csv
    import yaml
    import torch
    from ultralytics import settings

    torch.set_num_threads(2)
    settings.update({'sync':False})
    started = time.perf_counter()
    rows = [json.loads(line) for line in a.detections.read_text(encoding='utf8').splitlines()]
    config = yaml.safe_load(a.config.read_text(encoding='utf8'))
    if config['tracker_type'] != 'bytetrack':
        raise ValueError('Only the genuine ByteTrack implementation is used')
    frames, statistics = associate(rows, config, a.max_gap_seconds)
    with (out/'tracks.jsonl').open('w', encoding='utf8') as f:
        for frame in frames:
            f.write(json.dumps(frame, ensure_ascii=False)+'\n')
    with (ROOT/'docs/data/video-parts.csv').open(encoding='utf-8-sig', newline='') as f:
        part = next(p for p in csv.DictReader(f) if p['partId']==rows[0]['partId'] and p['matchId']==rows[0]['matchId'])
    root = a.event_root.resolve(strict=True)
    source = (root/part['relativePath']).resolve(strict=True)
    if not source.is_relative_to(root):
        raise ValueError('Source escaped event root')
    before = (source.stat().st_size,source.stat().st_mtime_ns)
    print('ASSOCIATED', len(rows), 'frames', len(statistics['tracks']), 'tracklets', flush=True)
    render(frames, source, out)
    if (source.stat().st_size,source.stat().st_mtime_ns)!=before:
        raise RuntimeError('Source file changed')
    summaries = statistics['tracks']
    input_run_path = a.detections.parent/'run.json'
    input_metadata = json.loads(input_run_path.read_text(encoding='utf8')) if input_run_path.exists() else {}
    return {'status':'COMPLETED','versions':{p:importlib.metadata.version(p) for p in ['ultralytics','lap','numpy','scipy','av']},
            'config':config,'associationDevice':'CPU','yoloExecutedByThisScript':False,
            'inputDetectionConfidenceThreshold':input_metadata.get('confidenceThreshold'),
            'matchId':part['matchId'],'partId':part['partId'],'sourceAlias':part['eventId']+'/'+part['relativePath'],
            'inputSha256':hashlib.sha256(a.detections.read_bytes()).hexdigest(),
            'inputFrames':len(rows),'inputDetections':sum(len(r['detections']) for r in rows),
            'framesWithTracks':sum(bool(f['tracks']) for f in frames),'trackObservations':sum(len(f['tracks']) for f in frames),
            'uniqueTracklets':len(summaries),'multiFrameTracklets':sum(t['observedFrames']>1 for t in summaries),
            'spanSeconds':{'min':min(t['spanSeconds'] for t in summaries),'median':median(t['spanSeconds'] for t in summaries),'max':max(t['spanSeconds'] for t in summaries)},
            'reappearanceGapCount':sum(len(t['reappearances']) for t in summaries),
            'elapsedSeconds':time.perf_counter()-started,'sourceStatUnchanged':True,
            'identityMetrics':{'idSwitches':None,'MOTA':None,'IDF1':None},**statistics}


def main():
    a = arguments()
    os.environ['CUDA_VISIBLE_DEVICES'] = '-1'
    os.environ['YOLO_CONFIG_DIR'] = str(ROOT/'artifacts/real-yolo-smoke/settings')
    Path(os.environ['YOLO_CONFIG_DIR']).mkdir(parents=True,exist_ok=True)
    os.environ['YOLO_AUTOINSTALL'] = 'false'
    os.environ['YOLO_OFFLINE'] = 'true'
    os.environ.setdefault('OMP_NUM_THREADS','2')
    os.environ.setdefault('MKL_NUM_THREADS','2')
    out = ROOT/'artifacts/real-bytetrack'/a.run_name
    out.mkdir(parents=True,exist_ok=False)
    try:
        result = run(a,out)
    except Exception as exc:
        result = {'status':'FAILED','errorType':type(exc).__name__,'error':str(exc),'mockFallback':False}
        (out/'run.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8')
        print(json.dumps(result,ensure_ascii=False),file=sys.stderr)
        return 1
    (out/'run.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8')
    print(json.dumps({k:v for k,v in result.items() if k!='tracks'},ensure_ascii=False),flush=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
