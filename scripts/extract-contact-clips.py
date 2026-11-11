"""Extract a small real P3 review bundle; original candidates remain intact."""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'ai-service'))
from app.contact_clips import plan_windows, extract_windows, union_intervals


def run(args, output):
    started = time.perf_counter()
    raw = args.candidates.read_bytes()
    events = json.loads(raw)['methods']['combined']
    run_record = json.loads((args.candidates.parent/'run.json').read_text(encoding='utf8'))
    config = json.loads(args.config.read_text(encoding='utf8'))
    start, end = run_record['startTimeMs'], run_record['endTimeMs']
    windows = plan_windows(events,start,end,**config)
    with (ROOT/'docs/data/video-parts.csv').open(encoding='utf-8-sig',newline='') as f:
        parts = [p for p in csv.DictReader(f) if p['matchId']==events[0]['matchId'] and p['partId']==events[0]['partId']]
    if len(parts)!=1:
        raise ValueError('Unique original match/part mapping is required')
    part = parts[0]
    source = (args.event_root/part['relativePath']).resolve(strict=True)
    source.relative_to(args.event_root.resolve(strict=True))
    result = extract_windows(source,windows,events,output)
    intervals = union_intervals((w['firstSourceTimeMs'],min(end,w['firstSourceTimeMs']+w['encodedDurationMs'])) for w in windows)
    coverage = sum(b-a for a,b in intervals)
    bindings = []
    for e in events:
        available = [w for w in windows if e['eventId'] in w['eventIds']]
        selected = min(available,key=lambda w:abs((w['startTimeMs']+w['endTimeMs'])/2-e['peakTimeMs'])) if available else None
        bindings.append(dict(candidate=e,clipId=selected['clipId'] if selected else None,
                             keyframe=f"{e['eventId']}.jpg" if selected else None,
                             clipPeakTimeMs=e['peakTimeMs']-selected['firstSourceTimeMs'] if selected else None))
    summary = dict(originalCandidates=len(events),boundCandidates=sum(b['clipId'] is not None for b in bindings),
                   clips=len(windows),encodedTotalDurationMs=result['encodedTotalDurationMs'],
                   reviewIntervals=intervals,reviewUnionDurationMs=coverage,sourceSampleDurationMs=end-start,
                   reviewFraction=coverage/(end-start),sourceStatUnchanged=True)
    bundle = dict(schema='framecall.contact-clips.v1',method='combined',source=dict(matchId=part['matchId'],partId=part['partId'],
                  segmentId=events[0]['segmentId'],event=part['event'],relativePath=part['relativePath'],
                  sampleStartTimeMs=start,sampleEndTimeMs=end,candidatesSha256=hashlib.sha256(raw).hexdigest()),
                  config=config,summary=summary,clips=windows,events=bindings,media=result['media'],
                  elapsedSeconds=time.perf_counter()-started,audio='VIDEO_ONLY',
                  limitation='Review priority only; no automatic bodily contact or foul decision; motion may be unavailable')
    (output/'bundle.json').write_text(json.dumps(bundle,ensure_ascii=False,indent=2,allow_nan=False)+'\n',encoding='utf8')
    print('COMPLETED',json.dumps(summary),flush=True)


if __name__=='__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidates',type=Path,default=ROOT/'artifacts/real-contact-candidates/p3-60s-final/candidates.json')
    parser.add_argument('--config',type=Path,default=ROOT/'config/contact-clips.json')
    parser.add_argument('--event-root',type=Path,required=True)
    parser.add_argument('--run-name',required=True)
    args = parser.parse_args()
    if Path(args.run_name).name!=args.run_name or args.run_name in {'.','..'}:
        parser.error('Use a new single output directory name')
    output = ROOT/'artifacts/real-contact-clips'/args.run_name
    output.mkdir(parents=True,exist_ok=False)
    try:
        run(args,output)
    except Exception as error:
        (output/'failure.json').write_text(json.dumps({'status':'FAILED','type':type(error).__name__,'error':str(error)}),encoding='utf8')
        raise
