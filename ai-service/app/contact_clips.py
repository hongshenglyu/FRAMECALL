"""PTS-based review windows from saved contact candidates, without model execution."""
from __future__ import annotations

import hashlib
from fractions import Fraction
from pathlib import Path


def union_intervals(intervals):
    merged = []
    for start, end in sorted(intervals):
        if end <= start:
            continue
        if merged and start <= merged[-1][1]:
            merged[-1][1] = max(end, merged[-1][1])
        else:
            merged.append([start, end])
    return merged


def plan_windows(events, start_ms, end_ms, count=8, before_ms=1500, after_ms=2000, overlap=0.60):
    if not events or end_ms <= start_ms or count < 1:
        raise ValueError('Nonempty candidates and a positive observation interval are required')
    if len({(e['matchId'], e['partId'], e['segmentId']) for e in events}) != 1:
        raise ValueError('One continuous match/part/segment per extraction; never join gaps')
    ids = [e['eventId'] for e in events]
    if len(ids) != len(set(ids)):
        raise ValueError('Duplicate original EventID')
    for e in events:
        if not start_ms <= e['startTimeMs'] <= e['peakTimeMs'] <= e['endTimeMs'] <= end_ms:
            raise ValueError('Candidate is outside the observed source interval')
    def rank(e):
        coverage = sum(e[k]['value'] is not None for k in ['closingSpeedEvidence', 'relativeVelocityChangeEvidence'])
        return coverage, e['candidateScore'], e['eventId']
    chosen = []
    # Temporal strata prevent all samples coming from the same high-scoring duplicate IDs.
    for bin_index in range(count):
        low = start_ms + (end_ms-start_ms)*bin_index/count
        high = start_ms + (end_ms-start_ms)*(bin_index+1)/count
        group = [e for e in events if low <= e['peakTimeMs'] < high or bin_index == count-1 and e['peakTimeMs'] == high]
        if group:
            chosen.append(max(group, key=rank))
    windows = []
    for e in sorted(chosen, key=lambda e:e['peakTimeMs']):
        start, end = max(start_ms, e['startTimeMs']-before_ms), min(end_ms, e['endTimeMs']+after_ms)
        previous = windows[-1] if windows else None
        intersection = max(0, min(end, previous['endTimeMs'])-max(start, previous['startTimeMs'])) if previous else 0
        if previous and intersection/min(end-start, previous['endTimeMs']-previous['startTimeMs']) >= overlap:
            previous['startTimeMs'] = min(start, previous['startTimeMs'])
            previous['endTimeMs'] = max(end, previous['endTimeMs'])
            previous['anchorEventIds'].append(e['eventId'])
        else:
            windows.append(dict(startTimeMs=start, endTimeMs=end, anchorEventIds=[e['eventId']]))
    for i, w in enumerate(windows):
        w['clipId'] = f'window-{i+1:02d}'
        w['eventIds'] = [e['eventId'] for e in events if w['startTimeMs'] <= e['startTimeMs'] and e['endTimeMs'] <= w['endTimeMs'] and e['peakTimeMs'] < w['endTimeMs']]
        if not set(w['anchorEventIds']).issubset(w['eventIds']):
            raise ValueError('Window boundary excludes an anchor event')
    return windows


def extract_windows(source, windows, events, output):
    import av
    import cv2
    import numpy as np

    source, output = Path(source), Path(output)
    source_stat = source.stat()
    by_id = {e['eventId']:e for e in events}
    selected = {eid for w in windows for eid in w['eventIds']}
    keyframes = {}
    for eid in selected:
        e = by_id[eid]
        pts = Fraction(e['peakSourcePts'])*Fraction(e['sourceTimeBase'])
        keyframes.setdefault(pts, []).append(eid)
    encoders = {}
    pts_by_clip = {w['clipId']:[] for w in windows}
    saved_images = set()
    tb = Fraction(1, 90000)
    try:
        with av.open(str(source)) as container:
            stream = container.streams.video[0]
            stream.thread_count = 2
            physical_start = float(stream.start_time*stream.time_base*1000) if stream.start_time is not None else 0
            physical_end = physical_start+float(stream.duration*stream.time_base*1000) if stream.duration is not None else None
            if any(w['startTimeMs'] < physical_start or physical_end is not None and w['endTimeMs'] > physical_end for w in windows):
                raise ValueError('Requested clip exceeds physical video bounds')
            first = min(w['startTimeMs'] for w in windows)
            last = max(w['endTimeMs'] for w in windows)
            container.seek(int(Fraction(str(first))/1000/stream.time_base), stream=stream)
            for frame in container.decode(stream):
                pts = frame.pts*frame.time_base
                ms = float(pts*1000)
                if ms >= last:
                    break
                active = [w for w in windows if w['startTimeMs'] <= ms < w['endTimeMs']]
                if not active:
                    continue
                pixels = frame.to_ndarray(format='bgr24')
                for eid in keyframes.get(pts, []):
                    success, encoded = cv2.imencode('.jpg', pixels)
                    if not success:
                        raise RuntimeError('Could not encode actual source keyframe')
                    (output/f'{eid}.jpg').write_bytes(encoded.tobytes())
                    saved_images.add(eid)
                for w in active:
                    cid = w['clipId']
                    if cid not in encoders:
                        if not w['eventIds']:
                            success, preview = cv2.imencode('.jpg', pixels)
                            if not success:
                                raise RuntimeError('Could not encode source sampling preview')
                            (output/f'{cid}.jpg').write_bytes(preview.tobytes())
                            w['previewTimeMs'] = ms
                        target = av.open(str(output/f'{cid}.mp4'), mode='w', options={'movflags':'+faststart'})
                        encoder = target.add_stream('libx264', rate=stream.average_rate)
                        encoder.width, encoder.height = frame.width, frame.height
                        encoder.pix_fmt = 'yuv420p'
                        encoder.time_base = encoder.codec_context.time_base = tb
                        encoder.codec_context.thread_count = 2
                        encoder.options = {'preset':'veryfast','crf':'23','tune':'zerolatency'}
                        encoders[cid] = (target, encoder, pts)
                    target, encoder, first_pts = encoders[cid]
                    encoded_frame = av.VideoFrame.from_ndarray(pixels, format='bgr24')
                    encoded_frame.pts, encoded_frame.time_base = round((pts-first_pts)/tb), tb
                    for packet in encoder.encode(encoded_frame):
                        target.mux(packet)
                    pts_by_clip[cid].append(dict(sourcePts=frame.pts, sourceTimeBase=str(frame.time_base), timeMs=ms,
                                                 durationMs=float(frame.duration*frame.time_base*1000)))
        for target, encoder, _ in encoders.values():
            for packet in encoder.encode():
                target.mux(packet)
    finally:
        for target, _, _ in encoders.values():
            target.close()
    if saved_images != selected:
        raise RuntimeError('Not every bound candidate peak was found in the real source')
    media = []
    encoded_total_ms = 0
    for w in windows:
        cid = w['clipId']
        original_pts = pts_by_clip[cid]
        if not original_pts:
            raise RuntimeError('Empty extracted clip')
        decoded = 0
        peak_errors = []
        peak_ids = {by_id[eid]['peakSourcePts']:eid for eid in w['eventIds']}
        with av.open(str(output/f'{cid}.mp4')) as result:
            result.streams.video[0].thread_count = 2
            for f in result.decode(video=0):
                row = original_pts[decoded]
                expected_ms = row['timeMs']-original_pts[0]['timeMs']
                if abs(float(f.pts*f.time_base*1000)-expected_ms)>0.1:
                    raise RuntimeError('Encoded MP4 timestamps differ from original PTS sequence')
                if row['sourcePts'] in peak_ids:
                    eid = peak_ids[row['sourcePts']]
                    jpg = cv2.imdecode(np.frombuffer((output/f'{eid}.jpg').read_bytes(), dtype=np.uint8), cv2.IMREAD_COLOR)
                    reference = cv2.resize(jpg,(320,180)).astype(float)
                    actual = cv2.resize(f.to_ndarray(format='bgr24'),(320,180)).astype(float)
                    error = float(np.abs(reference-actual).mean())
                    if error > 12:
                        raise RuntimeError('Encoded peak is inconsistent with the original keyframe')
                    peak_errors.append(error)
                decoded += 1
            duration_ms = result.duration/1000
        if decoded != len(original_pts):
            raise RuntimeError('MP4 could not decode all source frames')
        w.update(file=f'{cid}.mp4',firstSourceTimeMs=original_pts[0]['timeMs'],lastSourceTimeMs=original_pts[-1]['timeMs'],
                 encodedDurationMs=duration_ms,frames=decoded,peakPixelMeanErrorMax=max(peak_errors,default=None),
                 sourceFrames=original_pts)
        encoded_total_ms += duration_ms
        print(f"VERIFIED {cid} frames={decoded} events={len(w['eventIds'])} duration={duration_ms/1000:.3f}s", flush=True)
    for path in sorted(output.iterdir()):
        if path.suffix in {'.mp4','.jpg'}:
            media.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),sizeBytes=path.stat().st_size))
    after_stat = source.stat()
    if (source_stat.st_size,source_stat.st_mtime_ns)!=(after_stat.st_size,after_stat.st_mtime_ns):
        raise RuntimeError('Source file changed during extraction')
    return dict(media=media,encodedTotalDurationMs=encoded_total_ms,sourceStatUnchanged=True)
