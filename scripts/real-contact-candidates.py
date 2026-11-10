"""Analyze saved real ByteTrack observations; no YOLO, tracking, or foul decision."""
from __future__ import annotations

import argparse
import csv
from fractions import Fraction
import hashlib
import importlib.metadata
import json
from pathlib import Path
import platform
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'ai-service'))
from app.contact_candidates import analyze_frames


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False)+'\n', encoding='utf8')


def write_jpeg(path, image):
    import cv2
    success, encoded = cv2.imencode('.jpg',image)
    if not success:
        raise RuntimeError('Could not encode review image')
    path.write_bytes(encoded.tobytes())  # Python paths support Windows Unicode directories.


def arguments():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--tracks', type=Path, default=ROOT/'artifacts/real-bytetrack/p2-60s-lowconf/tracks.jsonl')
    p.add_argument('--config', type=Path, default=ROOT/'config/contact-candidates.json')
    p.add_argument('--event-root', type=Path, required=True)
    p.add_argument('--run-name', required=True)
    p.add_argument('--review-count', type=int, default=10)
    a = p.parse_args()
    if Path(a.run_name).name != a.run_name or a.run_name in {'.','..'} or a.review_count<1:
        p.error('Use a new single directory name and positive review count')
    return a


def select_reviews(methods, count):
    # Spread combined examples through time, then include distance-only evidence.
    combined = sorted(methods['combined'], key=lambda e:e['peakTimeMs'])
    selected = []
    n = min(len(combined), max(1, count-2))
    if n:
        selected = [combined[round(i*(len(combined)-1)/max(1,n-1))] for i in range(n)]
    baseline = sorted(methods['distance-baseline'], key=lambda e:-e['candidateScore'])
    signatures = {(tuple(e['peakEvidence']['pairTrackKeys']),e['peakFrameIndex']) for e in selected}
    for event in baseline:
        signature = (tuple(event['peakEvidence']['pairTrackKeys']),event['peakFrameIndex'])
        if len(selected)>=count:
            break
        if signature not in signatures:
            selected.append(event)
            signatures.add(signature)
    return selected


def render_reviews(frames, methods, source, out, count):
    import av
    import cv2
    import numpy as np

    selected = select_reviews(methods, count)
    if not selected:
        return {'status':'NO_CANDIDATES', 'reviews':[]}
    targets, review_rows = {}, []
    for event in selected:
        scoped = [f for f in frames if (f['matchId'],f['partId'],f['segmentId']) ==
                  (event['matchId'],event['partId'],event['segmentId'])]
        review = {'eventId':event['eventId'], 'method':event['method'], 'frames':[]}
        for phase, delta in [('before',-500),('peak',0),('after',500)]:
            row = min(scoped, key=lambda f:abs(f['timeMs']-(event['peakTimeMs']+delta)))
            if phase=='peak' and row['frameIndex']!=event['peakFrameIndex']:
                raise RuntimeError('Peak cannot be matched to source row')
            filename = f"{event['eventId']}-{phase}.jpg"
            item = dict(phase=phase,frameIndex=row['frameIndex'],timeMs=row['timeMs'],
                        sourcePts=row['sourcePts'],sourceTimeBase=row['sourceTimeBase'],file=filename)
            review['frames'].append(item)
            pts = Fraction(row['sourcePts'])*Fraction(row['sourceTimeBase'])
            targets.setdefault(pts,[]).append((event,row,item))
        review_rows.append(review)
    first, last = min(targets), max(targets)
    rendered, thumbnails = 0, {}
    before_stat = source.stat()
    with av.open(str(source)) as original:
        stream = original.streams.video[0]
        stream.thread_count = 2
        original.seek(int(first/stream.time_base),stream=stream)
        for video_frame in original.decode(stream):
            pts = video_frame.pts*video_frame.time_base
            if pts>last:
                break
            if pts not in targets:
                continue
            image = video_frame.to_ndarray(format='bgr24')
            for event,row,item in targets.pop(pts):
                drawn = image.copy()
                keys = event['peakEvidence']['pairTrackKeys']
                points, boxes = [], []
                for track in row['tracks']:
                    if track['trackKey'] not in keys:
                        continue
                    x1,y1,x2,y2 = [round(track[k]) for k in ['x1','y1','x2','y2']]
                    color = (0,220,255) if track['trackKey']==keys[0] else (255,200,0)
                    cv2.rectangle(drawn,(x1,y1),(x2,y2),color,2)
                    cv2.putText(drawn,f"T{track['trackId']}",(x1,max(16,y1-8)),cv2.FONT_HERSHEY_SIMPLEX,0.7,color,2)
                    point = (round((x1+x2)/2),y2)
                    cv2.circle(drawn,point,5,color,-1)
                    points.append(point)
                    boxes.append((x1,y1,x2,y2))
                if len(points)==2:
                    cv2.line(drawn,points[0],points[1],(0,80,255),2)
                label = f"REVIEW ONLY | {event['method']} | {item['phase']} | F{row['frameIndex']} {row['timeMs']/1000:.3f}s"
                cv2.putText(drawn,label,(20,30),cv2.FONT_HERSHEY_SIMPLEX,0.65,(0,255,255),2)
                write_jpeg(out/item['file'],drawn)
                item['visibleSelectedTrackKeys'] = [t['trackKey'] for t in row['tracks'] if t['trackKey'] in keys]
                if item['phase']=='peak':
                    if len(boxes)!=2:
                        raise RuntimeError('Peak evidence pair not present in source track row')
                    x1 = max(0,min(b[0] for b in boxes)-100)
                    y1 = max(40,min(b[1] for b in boxes)-70)
                    x2 = min(drawn.shape[1],max(b[2] for b in boxes)+100)
                    y2 = min(drawn.shape[0],max(b[3] for b in boxes)+70)
                    crop = cv2.resize(drawn[y1:y2,x1:x2],(640,360))
                    tile = np.zeros((430,640,3),dtype=np.uint8)
                    tile[:360] = crop
                    text = f"{event['eventId']} | {event['method']}"
                    cv2.putText(tile,text,(8,383),cv2.FONT_HERSHEY_SIMPLEX,0.47,(255,255,255),1)
                    cv2.putText(tile,f"t={row['timeMs']/1000:.3f}s d={event['peakEvidence']['normalizedDistance']:.3f} score={event['candidateScore']:.3f}",
                                (8,407),cv2.FONT_HERSHEY_SIMPLEX,0.55,(255,255,255),1)
                    thumbnails[event['eventId']] = tile
                rendered += 1
            if not targets:
                break
    if targets:
        raise RuntimeError('Source PTS missing during keyframe rendering')
    sheets = []
    for offset in range(0,len(selected),6):
        page = selected[offset:offset+6]
        sheet = np.zeros((((len(page)+1)//2)*430,1280,3),dtype=np.uint8)
        for i,event in enumerate(page):
            sheet[(i//2)*430:(i//2+1)*430,(i%2)*640:(i%2+1)*640] = thumbnails[event['eventId']]
        name = f'review-sheet-{offset//6+1}.jpg'
        write_jpeg(out/name,sheet)
        sheets.append(name)
    after_stat = source.stat()
    if (before_stat.st_size,before_stat.st_mtime_ns)!=(after_stat.st_size,after_stat.st_mtime_ns):
        raise RuntimeError('Source changed during read-only review rendering')
    return dict(status='RENDERED_NOT_YET_MANUALLY_REVIEWED',frames=rendered,sheets=sheets,reviews=review_rows,sourceStatUnchanged=True)


def run(a, out):
    started = time.perf_counter()
    config = json.loads(a.config.read_text(encoding='utf8'))
    rows = [json.loads(line) for line in a.tracks.read_text(encoding='utf8').splitlines() if line.strip()]
    if not rows or len({(r['matchId'],r['partId']) for r in rows})!=1:
        raise ValueError('CLI renders one non-empty match/part per invocation')
    with (ROOT/'docs/data/video-parts.csv').open(encoding='utf-8-sig',newline='') as f:
        parts = [p for p in csv.DictReader(f) if p['partId']==rows[0]['partId'] and p['matchId']==rows[0]['matchId']]
    if len(parts)!=1:
        raise ValueError('Expected unique manifest match/part')
    part = parts[0]
    source = (a.event_root/part['relativePath']).resolve(strict=True)
    source.relative_to(a.event_root.resolve(strict=True))
    calculation_started = time.perf_counter()
    with (out/'timeline.jsonl').open('w',encoding='utf8') as timeline:
        def emit(row):
            timeline.write(json.dumps(row,ensure_ascii=False,allow_nan=False,separators=(',',':'))+'\n')
        result = analyze_frames(iter(rows),config,emit)
    calculation_seconds = time.perf_counter()-calculation_started
    write_json(out/'candidates.json',result)
    counts = {k:len(v) for k,v in result['methods'].items()}
    print('ANALYZED',json.dumps({'statistics':result['statistics'],'candidates':counts}),flush=True)
    visuals = render_reviews(rows,result['methods'],source,out,a.review_count)
    write_json(out/'visuals.json',visuals)
    record = dict(status='COMPLETED',manualReview='PENDING',config=config,python=platform.python_version(),
                  versions={p:importlib.metadata.version(p) for p in ['av','numpy','opencv-python']},
                  inputSha256=hashlib.sha256(a.tracks.read_bytes()).hexdigest(),
                  sourceAlias=part['eventId']+'/'+part['relativePath'],
                  matchId=part['matchId'],partId=part['partId'],
                  startTimeMs=rows[0]['timeMs'],endTimeMs=rows[-1]['timeMs'],
                  statistics=result['statistics'],candidateCounts=counts,
                  calculationSeconds=calculation_seconds,elapsedSeconds=time.perf_counter()-started,
                  modelExecuted=False,trackingExecuted=False,device='CPU',
                  sourceStatUnchanged=visuals.get('sourceStatUnchanged'),
                  precision=None,recall=None,F1=None,
                  reproduction='python scripts/real-contact-candidates.py --event-root <event-root> --run-name <new-name>')
    write_json(out/'run.json',record)
    print('COMPLETED',json.dumps({'candidates':counts,'elapsedSeconds':record['elapsedSeconds'],'keyframes':visuals.get('frames',0)}),flush=True)


if __name__=='__main__':
    args = arguments()
    output = ROOT/'artifacts/real-contact-candidates'/args.run_name
    output.mkdir(parents=True,exist_ok=False)
    try:
        run(args,output)
    except Exception as error:
        write_json(output/'run.json',{'status':'FAILED','errorType':type(error).__name__,'error':str(error)})
        raise
