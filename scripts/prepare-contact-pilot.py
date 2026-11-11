"""Prepare unlabeled real pilot material. No generated contact labels or human observations."""
import argparse
import csv
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'ai-service'))
from app.contact_clips import extract_windows


def sample_record(number, clip_id, reasons, candidate_ids):
    return dict(sampleId=f'P5-S{number:02d}',clipId=clip_id,selectionReasons=reasons,basedOnCandidateIds=candidate_ids,
                split='DEVELOPMENT',annotationStatus='UNLABELED',contestStartTimeMs=None,firstContactTimeMs=None,
                contestEndTimeMs=None,contactVisibility=None,annotator=None,reviewStatus='PENDING',annotations=[],reviews=[],
                note='Selection context only; a person must inspect the footage. No contact or foul ground truth assigned.')


def prepare(args, output):
    old_dir = ROOT/'artifacts/real-contact-clips/p4-review-final'
    old = json.loads((old_dir/'bundle.json').read_text(encoding='utf8'))
    clips, samples = [], []
    for i, previous in enumerate(old['clips'],1):
        clip = deepcopy(previous)
        clip.update(clipId=f'pilot-{i:02d}',file=f'pilot-{i:02d}.mp4',originClipId=previous['clipId'])
        shutil.copyfile(old_dir/previous['file'],output/clip['file'])
        preview_id = previous['anchorEventIds'][0]
        clip['previewFile'] = f'pilot-{i:02d}.jpg'
        shutil.copyfile(old_dir/f'{preview_id}.jpg',output/clip['previewFile'])
        binding = next(b for b in old['events'] if b['candidate']['eventId']==preview_id)
        clip['previewTimeMs'] = binding['candidate']['peakTimeMs']
        clips.append(clip)
        reasons = ['ALGORITHM_CONTEXT_NOT_A_LABEL']
        if i==1: reasons.append('NORMAL_APPROACH_LEAD_REQUIRES_HUMAN_CONFIRMATION')
        if i in {3,4,7}: reasons.append('TRACK_CONFUSION_OR_DUPLICATION_LEAD_NOT_A_LABEL')
        samples.append(sample_record(i,clip['clipId'],reasons,previous['eventIds']))
    with (ROOT/'docs/data/video-parts.csv').open(encoding='utf-8-sig',newline='') as stream:
        part = next(p for p in csv.DictReader(stream) if p['partId']==old['source']['partId'])
    source = (args.event_root/part['relativePath']).resolve(strict=True)
    source.relative_to(args.event_root.resolve(strict=True))
    raw_windows = []
    # Fixed source-relative times, chosen without consulting P3 detections in these intervals.
    import av
    with av.open(str(source)) as original:
        stream=original.streams.video[0]
        if stream.start_time is None:
            raise ValueError('Source stream start PTS is unavailable')
        source_start_ms=float(stream.start_time*stream.time_base*1000)
    for i, seconds in enumerate([480,720,840,960,1080,1200,1320,1440,1560],8):
        if seconds+12>float(part['durationSeconds']):
            raise ValueError('Source part is too short for configured independent sampling')
        raw_windows.append(dict(clipId=f'pilot-{i:02d}',startTimeMs=source_start_ms+seconds*1000,
                                endTimeMs=source_start_ms+(seconds+12)*1000,eventIds=[],anchorEventIds=[]))
        samples.append(sample_record(i,f'pilot-{i:02d}',['SOURCE_SYSTEMATIC_SCAN_NOT_ALGORITHM_SELECTED'],[]))
    for window in raw_windows:
        extract_windows(source,[window],[],output)
    for clip in raw_windows:
        clip['previewFile'] = clip['clipId']+'.jpg'
    clips.extend(raw_windows)
    media = [dict(file=p.name,sha256=hashlib.sha256(p.read_bytes()).hexdigest(),sizeBytes=p.stat().st_size)
             for p in sorted(output.iterdir()) if p.suffix in {'.mp4','.jpg'}]
    source_info = {k:v for k,v in old['source'].items() if k not in {'sampleStartTimeMs','sampleEndTimeMs'}}
    source_info.update(sourceStartTimeMs=source_start_ms,partDurationMs=float(part['durationSeconds'])*1000)
    bundle = dict(schema='framecall.contact-pilot.v1',method='pilot-preparation',source=source_info,
                  samples=samples,clips=clips,media=media,purpose='PILOT_DEVELOPMENT_ONLY',
                  summary=dict(samples=16,algorithmContext=7,independentSourceWindows=9,humanAnnotated=0,unlabeled=16,
                               confirmedContactFreeContests=0,humanDiscoveredContests=0,formalGroundTruth=0),
                  limits=['Human discovery and contact visibility remain pending','Not a complete-match dataset',
                          'All parts of this match stay in DEVELOPMENT; never use it as an independent test match'])
    (output/'bundle.json').write_text(json.dumps(bundle,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    public = {k:v for k,v in bundle.items() if k not in {'clips','media'}}
    public['source'] = source_info
    for sample, clip in zip(public['samples'],clips):
        sample['sourceStartTimeMs'] = clip['firstSourceTimeMs']
        sample['sourceLastFrameTimeMs'] = clip['lastSourceTimeMs']
        sample['clipDurationMs'] = clip['encodedDurationMs']
        sample['sourceFrameBounds'] = dict(first=clip['sourceFrames'][0],last=clip['sourceFrames'][-1])
    (ROOT/'docs/data/contact-pilot-manifest.json').write_text(json.dumps(public,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    print(json.dumps(bundle['summary']),flush=True)


def prepare_selected(args, output):
    plan = json.loads(args.selection_plan.read_text(encoding='utf8'))
    original = json.loads((ROOT/'artifacts/contact-pilot/p5-16-ready/bundle.json').read_text(encoding='utf8'))
    if plan['partId'] != original['source']['partId']:
        raise ValueError('Supplement must stay in the existing development match/part')
    with (ROOT/'docs/data/video-parts.csv').open(encoding='utf-8-sig',newline='') as stream:
        part = next(p for p in csv.DictReader(stream) if p['partId']==plan['partId'])
    source = (args.event_root/part['relativePath']).resolve(strict=True)
    source.relative_to(args.event_root.resolve(strict=True))
    clips, samples = [], []
    for item in plan['windows']:
        start = original['source']['sourceStartTimeMs']+item['startSeconds']*1000
        end = start+item['durationSeconds']*1000
        if any(start<c['endTimeMs'] and end>c['startTimeMs'] for c in original['clips']+clips):
            raise ValueError('Supplement overlaps existing or another selected clip')
        number = item['sampleNumber']
        if not 17<=number<=99 or any(s['sampleId']==f'P5-S{number:02d}' for s in samples):
            raise ValueError('Use new unique sample numbers')
        clip = dict(clipId=f'pilot-{number:02d}',startTimeMs=start,endTimeMs=end,eventIds=[],anchorEventIds=[])
        extract_windows(source,[clip],[],output)
        clip['previewFile'] = clip['clipId']+'.jpg'
        clips.append(clip)
        sample = sample_record(number,clip['clipId'],['SOURCE_VISUAL_SELECTION_NOT_A_CONTACT_LABEL'],[])
        sample.update(selectionNotes=item['selectionNotes'],sourceStartTimeMs=clip['firstSourceTimeMs'],
                      sourceLastFrameTimeMs=clip['lastSourceTimeMs'],clipDurationMs=clip['encodedDurationMs'],
                      sourceFrameBounds=dict(first=clip['sourceFrames'][0],last=clip['sourceFrames'][-1]))
        samples.append(sample)
    media = [dict(file=p.name,sha256=hashlib.sha256(p.read_bytes()).hexdigest(),sizeBytes=p.stat().st_size)
             for p in sorted(output.iterdir()) if p.suffix in {'.mp4','.jpg'}]
    bundle = dict(schema='framecall.contact-pilot.v1',method='pilot-preparation',source=original['source'],
                  batchId=plan['batchId'],samples=samples,clips=clips,media=media,purpose='PILOT_DEVELOPMENT_ONLY',
                  selectionExclusions=plan.get('excludedAfterSequenceInspection',[]),
                  summary=dict(samples=len(samples),humanAnnotated=0,unlabeled=len(samples),formalGroundTruth=0),
                  limits=['Selection notes are leads, not human labels','No new detector/tracker execution; track anomalies unverified',
                          'Existing annotations and original 16 clips are unchanged; this match stays DEVELOPMENT'])
    (output/'bundle.json').write_text(json.dumps(bundle,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    public = {k:v for k,v in bundle.items() if k not in {'clips','media'}}
    public_path = output/'manifest.json'
    public_path.write_text(json.dumps(public,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    print(json.dumps(bundle['summary']),flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--event-root',type=Path,required=True)
    parser.add_argument('--run-name',required=True)
    parser.add_argument('--selection-plan',type=Path,help='Explicit source windows for an additional unlabeled development batch')
    args=parser.parse_args()
    if Path(args.run_name).name!=args.run_name or args.run_name in {'.','..'}: parser.error('Use a new single output name')
    output=ROOT/'artifacts/contact-pilot'/args.run_name
    output.mkdir(parents=True,exist_ok=False)
    try:
        if args.selection_plan: prepare_selected(args,output)
        else: prepare(args,output)
    except Exception as error:
        (output/'failure.json').write_text(json.dumps({'status':'FAILED','error':str(error)}),encoding='utf8')
        raise
