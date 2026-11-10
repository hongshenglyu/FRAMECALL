"""Image-plane review candidates from real tracks. No classifier, fallback, or identity stitching."""
from __future__ import annotations

from collections import Counter, deque
from fractions import Fraction
import hashlib
from itertools import combinations
import math
from statistics import median


def _norm(v):
    return math.hypot(*v)


def _slope(points):
    """Least-squares pixel velocity of relative positions using actual elapsed seconds."""
    times = [(p['timeMs']-points[0]['timeMs'])/1000 for p in points]
    center = sum(times)/len(times)
    denominator = sum((t-center)**2 for t in times)
    return tuple(sum((t-center)*p['relative'][axis] for t,p in zip(times,points))/denominator for axis in [0,1])


def _iou(a,b):
    intersection = max(0,min(a['x2'],b['x2'])-max(a['x1'],b['x1']))*max(0,min(a['y2'],b['y2'])-max(a['y1'],b['y1']))
    area = (a['x2']-a['x1'])*(a['y2']-a['y1'])+(b['x2']-b['x1'])*(b['y2']-b['y1'])
    return intersection/(area-intersection) if area>intersection else 1.0


def _available(history, minimum, span):
    return len(history)>=minimum and history[-1]['timeMs']-history[0]['timeMs']+1e-6>=span


class FeatureStream:
    def __init__(self, config):
        self.config = config
        self.scope = None
        self.previous_frame = None
        self.tracks = {}
        self.pairs = {}
        self.block_counter = 0
        self.stats = Counter()

    def _track(self, frame, track):
        m = self.config['motion']
        h = track['y2']-track['y1']
        p = ((track['x1']+track['x2'])/2,track['y2'])
        if h<=0 or track['x2']<=track['x1'] or not all(math.isfinite(v) for v in (*p,h,track['confidence'])):
            raise ValueError('Invalid track geometry or confidence')
        previous = self.tracks.get(track['trackKey'])
        flags = []
        if h<self.config['geometry']['min_height_px']:
            flags.append('SMALL_BOX')
        if track['confidence']<m['min_confidence']:
            flags.append('LOW_CONFIDENCE_FOR_MOTION')
        raw = track.get('detectionBbox')
        if raw and math.dist(p,((raw[0]+raw[2])/2,raw[3]))/h>m['max_filter_disagreement_heights']:
            flags.append('FILTER_DETECTION_DISAGREEMENT')
        if previous:
            dt = frame['timeMs']-previous['timeMs']
            if frame['frameIndex']!=previous['frameIndex']+1 or dt>m['max_step_ms']:
                flags.append('OBSERVATION_GAP')
            else:
                speed = math.dist(p,previous['point'])/((h+previous['height'])/2)/(dt/1000)
                if speed>m['max_step_speed_heights_s']:
                    flags.append('SUSPECTED_ID_OR_POSITION_JUMP')
                if max(h,previous['height'])/min(h,previous['height'])>m['max_height_step_ratio']:
                    flags.append('SCALE_JUMP')
        eligible = not any(f in flags for f in ['SMALL_BOX','LOW_CONFIDENCE_FOR_MOTION','FILTER_DETECTION_DISAGREEMENT'])
        if previous and previous['eligible'] and eligible and not flags:
            block = previous['block']
        else:
            self.block_counter += 1
            block = self.block_counter
        state = dict(point=p,height=h,timeMs=frame['timeMs'],frameIndex=frame['frameIndex'],eligible=eligible,block=block,flags=flags)
        self.tracks[track['trackKey']] = state
        self.stats.update(flags)
        return state

    def process(self, frame):
        scope = (frame['matchId'],frame['partId'],frame['segmentId'])
        if scope!=self.scope:
            self.scope,self.previous_frame = scope,None
            self.tracks,self.pairs = {},{}
        if self.previous_frame is not None:
            if frame['timeMs']<=self.previous_frame['timeMs'] or frame['frameIndex']<=self.previous_frame['frameIndex']:
                raise ValueError('Non-monotonic time/frame within a segment')
        if abs(frame['timeMs']-float(Fraction(frame['sourceTimeBase'])*frame['sourcePts']*1000))>0.001:
            raise ValueError('timeMs does not match actual source PTS')
        keys = [t['trackKey'] for t in frame['tracks']]
        if len(keys)!=len(set(keys)):
            raise ValueError('Duplicate trackKey within frame')
        tracks = sorted(frame['tracks'],key=lambda t:t['trackKey'])
        states = {t['trackKey']:self._track(frame,t) for t in tracks}
        m = self.config['motion']
        self.pairs = {k:v for k,v in self.pairs.items() if v and frame['timeMs']-v[-1]['timeMs']<=m['window_ms']}
        for a,b in combinations(tracks,2):
            ka,kb = a['trackKey'],b['trackKey']
            sa,sb = states[ka],states[kb]
            pair = (ka,kb)
            flags = sorted(set(sa['flags']+sb['flags']))
            scale = (sa['height']+sb['height'])/2
            relative = (sb['point'][0]-sa['point'][0],sb['point'][1]-sa['point'][1])
            distance = _norm(relative)/scale
            pair_bad = []
            if max(sa['height'],sb['height'])/min(sa['height'],sb['height'])>self.config['geometry']['max_height_ratio']:
                pair_bad.append('PERSPECTIVE_SCALE_MISMATCH')
            if _iou(a,b)>=self.config['geometry']['duplicate_iou']:
                pair_bad.append('POSSIBLE_DUPLICATE_PERSON_TRACKS')
            flags = sorted(set(flags+pair_bad))
            history = self.pairs.get(pair,deque())
            blocks = (sa['block'],sb['block'])
            if history and (history[-1]['frameIndex']!=frame['frameIndex']-1 or history[-1]['blocks']!=blocks):
                history.clear()
            point = dict(timeMs=frame['timeMs'],frameIndex=frame['frameIndex'],relative=relative,scale=scale,blocks=blocks)
            eligible = sa['eligible'] and sb['eligible'] and not pair_bad
            if eligible:
                history.append(point)
                while history and frame['timeMs']-history[0]['timeMs']>m['window_ms']:
                    history.popleft()
            else:
                history.clear()
            self.pairs[pair] = history
            closing = change = None
            closing_reason = change_reason = 'INSUFFICIENT_CONTIGUOUS_SAMPLES'
            support = None
            if not eligible:
                closing_reason = change_reason = 'QUALITY_BLOCKED:'+','.join(flags)
            elif _available(history,m['min_samples'],m['min_span_ms']):
                points = list(history)
                local_scale = median(p['scale'] for p in points)
                velocity = _slope(points)
                if _norm(relative)>1e-9:
                    closing = -sum(x*v for x,v in zip(relative,velocity))/_norm(relative)/local_scale
                    closing_reason = None
                else:
                    closing_reason = 'COINCIDENT_REPRESENTATIVE_POINTS'
                support = dict(startTimeMs=points[0]['timeMs'],endTimeMs=points[-1]['timeMs'],samples=len(points))
                if _available(points,m['change_min_samples'],m['change_min_span_ms']):
                    half = len(points)//2
                    before,after = points[:half],points[half:]
                    if _available(before,3,m['half_min_span_ms']) and _available(after,3,m['half_min_span_ms']):
                        va,vb = _slope(before),_slope(after)
                        change = math.dist(va,vb)/local_scale
                        change_reason = None
                    else:
                        change_reason = 'INSUFFICIENT_BEFORE_AFTER_SPAN'
                else:
                    change_reason = 'INSUFFICIENT_VELOCITY_CHANGE_WINDOW'
            digest = hashlib.sha256('|'.join(pair).encode()).hexdigest()[:12]
            yield dict(evidenceId=f"{frame['matchId']}:{frame['partId']}:S{scope[2]}:F{frame['frameIndex']}:{digest}",
                       matchId=scope[0],partId=scope[1],segmentId=scope[2],frameIndex=frame['frameIndex'],timeMs=frame['timeMs'],
                       sourcePts=frame['sourcePts'],sourceTimeBase=frame['sourceTimeBase'],pairTrackKeys=list(pair),
                       points=[list(sa['point']),list(sb['point'])],pairScalePx=scale,
                       pairCenter=[(sa['point'][i]+sb['point'][i])/2 for i in [0,1]],
                       normalizedDistance=distance,closingSpeedHeightPerSecond=closing,closingUnavailableReason=closing_reason,
                       relativeVelocityChangeHeightPerSecond=change,velocityChangeUnavailableReason=change_reason,
                       motionSupport=support,trackBlocks=list(blocks),qualityFlags=flags)
        self.previous_frame = frame


def _score(row, config, enabled):
    s = config['scoring']
    evidence = {'distance':max(0.0,1-row['normalizedDistance']/s['near_distance']),
                'closing_speed':min(1.0,max(0.0,row['closingSpeedHeightPerSecond'])/s['closing_reference_heights_s']) if row['closingSpeedHeightPerSecond'] is not None else None,
                'relative_velocity_change':min(1.0,row['relativeVelocityChangeHeightPerSecond']/s['change_reference_heights_s']) if row['relativeVelocityChangeHeightPerSecond'] is not None else None}
    weight_sum = sum(s['weights'][k] for k in evidence if enabled[k])
    components = {k:(s['weights'][k]/weight_sum*(value if value is not None else 0)) if enabled[k] else 0 for k,value in evidence.items()}
    return sum(components.values()),components


def _scope(row):
    return (row['matchId'],row['partId'],row['segmentId'])


def _episode(row):
    return {'scope':_scope(row),'pair':tuple(row['pairTrackKeys']),'samples':[row]}


def _ranges(samples, max_step_ms):
    ranges = []
    for r in sorted(samples,key=lambda r:r['frameIndex']):
        if ranges and r['frameIndex']==ranges[-1]['endFrameIndex']+1 and r['timeMs']-ranges[-1]['endTimeMs']<=max_step_ms:
            ranges[-1].update(endFrameIndex=r['frameIndex'],endTimeMs=r['timeMs'])
        else:
            ranges.append(dict(startFrameIndex=r['frameIndex'],endFrameIndex=r['frameIndex'],startTimeMs=r['timeMs'],endTimeMs=r['timeMs']))
    return ranges


def _events(episodes, method, config):
    """Coalesce event evidence only; original track IDs and motion histories are never stitched."""
    episodes = [e for e in episodes if len(e['samples'])>=config['merging']['minimum_support_frames']]
    episodes.sort(key=lambda e:(e['scope'],e['samples'][0]['timeMs'],e['pair']))
    groups = []
    for episode in episodes:
        start = episode['samples'][0]
        destination = None
        for group in reversed(groups):
            if group['scope']!=episode['scope']:
                continue
            shared = group['keys'].intersection(episode['pair'])
            if not shared or start['timeMs']-group['last']['timeMs']>config['merging']['shared_anchor_gap_ms']:
                continue
            nearby = [r for e in group['episodes'] if set(e['pair']).intersection(episode['pair']) for r in e['samples']
                      if abs(r['timeMs']-start['timeMs'])<=config['merging']['shared_anchor_gap_ms']]
            if not any(math.dist(start['pairCenter'],r['pairCenter'])/((start['pairScalePx']+r['pairScalePx'])/2)
                       <=config['merging']['shared_anchor_distance_heights'] for r in nearby):
                continue
            destination = group
            break
        if destination is None:
            destination = dict(scope=episode['scope'],keys=set(),episodes=[],last=start)
            groups.append(destination)
        destination['keys'].update(episode['pair'])
        destination['episodes'].append(episode)
        if episode['samples'][-1]['timeMs']>=destination['last']['timeMs']:
            destination['last'] = episode['samples'][-1]
    events = []
    for group in groups:
        samples = sorted((r for e in group['episodes'] for r in e['samples']),key=lambda r:(r['timeMs'],r['evidenceId']))
        peak = max(samples,key=lambda r:(r['scores'][method],-r['normalizedDistance']))
        scope = group['scope']
        participants = []
        for key in sorted(group['keys']):
            relevant = {r['evidenceId']:r for r in samples if key in r['pairTrackKeys']}
            observed = {r['frameIndex']:r for r in relevant.values()}
            blocks = {}
            for row in relevant.values():
                if row['motionSupport'] and (row['closingSpeedHeightPerSecond'] is not None or row['relativeVelocityChangeHeightPerSecond'] is not None):
                    bid = row['trackBlocks'][row['pairTrackKeys'].index(key)]
                    support = row['motionSupport']
                    entry = blocks.setdefault(bid,dict(blockId=bid,startTimeMs=support['startTimeMs'],endTimeMs=support['endTimeMs'],identityVerified=False))
                    entry['startTimeMs'] = min(entry['startTimeMs'],support['startTimeMs'])
                    entry['endTimeMs'] = max(entry['endTimeMs'],support['endTimeMs'])
            participants.append(dict(trackKey=key,observedRanges=_ranges(observed.values(),config['motion']['max_step_ms']),localMotionSupportRanges=list(blocks.values()),
                                     trustMeaning='Local continuity/jump heuristics only; physical identity is unverified'))
        def evidence(value_key, reason_key):
            valid = [r for r in samples if r[value_key] is not None]
            chosen = max(valid,key=lambda r:r[value_key]) if valid else None
            return dict(value=chosen[value_key] if chosen else None,timeMs=chosen['timeMs'] if chosen else None,
                        support=chosen['motionSupport'] if chosen else None,
                        unavailableReasons=dict(Counter(r[reason_key] for r in samples if r[reason_key])))
        flags = sorted({flag for r in samples for flag in r['qualityFlags']}|{'TRACK_IDENTITY_UNVERIFIED','IMAGE_PLANE_APPROXIMATION'})
        if len(group['keys'])>2:
            flags.append('SHARED_ANCHOR_EVENT_MERGE_NOT_IDENTITY_STITCH')
        minimum = min(samples,key=lambda r:r['normalizedDistance'])
        identity = f"{method}|{scope}|{samples[0]['timeMs']}|{samples[-1]['timeMs']}|{sorted(group['keys'])}"
        event = dict(eventId='CONTACT-'+hashlib.sha256(identity.encode()).hexdigest()[:16],method=method,
                     matchId=scope[0],partId=scope[1],segmentId=scope[2],
                     startTimeMs=samples[0]['timeMs'],peakTimeMs=peak['timeMs'],endTimeMs=samples[-1]['timeMs'],
                     peakFrameIndex=peak['frameIndex'],peakSourcePts=peak['sourcePts'],sourceTimeBase=peak['sourceTimeBase'],
                     trackParticipants=participants,minNormalizedDistance=minimum['normalizedDistance'],minDistanceTimeMs=minimum['timeMs'],
                     closingSpeedEvidence=evidence('closingSpeedHeightPerSecond','closingUnavailableReason'),
                     relativeVelocityChangeEvidence=evidence('relativeVelocityChangeHeightPerSecond','velocityChangeUnavailableReason'),
                     candidateScore=peak['scores'][method],scoreMeaning='REVIEW_PRIORITY_NOT_CONTACT_OR_FOUL_PROBABILITY',
                     peakMeaning='MAX_COMPUTED_REVIEW_EVIDENCE_NOT_FIRST_VISIBLE_CONTACT',contactStatus='NOT_DETERMINED',foulDecision='NOT_ASSESSED',
                     peakEvidence=peak,qualityFlags=flags,
                     pairEpisodes=[dict(pairTrackKeys=list(e['pair']),startTimeMs=e['samples'][0]['timeMs'],endTimeMs=e['samples'][-1]['timeMs'],
                                        evidenceIntervals=_ranges(e['samples'],config['motion']['max_step_ms']),supportFrames=len(e['samples'])) for e in group['episodes']],
                     evidenceRefs=[r['evidenceId'] for r in samples])
        if any(len(e['evidenceIntervals'])>1 for e in event['pairEpisodes']):
            event['qualityFlags'].append('EVIDENCE_GAPS_NOT_INTERPOLATED')
        events.append(event)
    return events


def analyze_frames(frames, config, emit=None):
    """Consume real or test track rows; zero candidates remains zero, never a fallback event."""
    engine = FeatureStream(config)
    active = {method:{} for method in config['methods']}
    finished = {method:[] for method in config['methods']}
    counts = Counter()
    unavailable = Counter()
    for frame in frames:
        counts['frames'] += 1
        counts['trackObservations'] += len(frame['tracks'])
        for row in engine.process(frame):
            counts['pairObservations'] += 1
            for value,reason in [('closingSpeedHeightPerSecond','closingUnavailableReason'),('relativeVelocityChangeHeightPerSecond','velocityChangeUnavailableReason')]:
                if row[value] is not None:
                    counts[value+'_available'] += 1
                else:
                    unavailable[row[reason]] += 1
            row['scores'],row['scoreComponents'] = {},{}
            row['selectedBy'] = []
            for method,enabled in config['methods'].items():
                score,components = _score(row,config,enabled)
                row['scores'][method],row['scoreComponents'][method] = score,components
                if row['normalizedDistance']>config['scoring']['near_distance'] or score<config['scoring']['minimum_score']:
                    continue
                row['selectedBy'].append(method)
                key = (_scope(row),tuple(row['pairTrackKeys']))
                previous = active[method].get(key)
                if previous and row['timeMs']-previous['samples'][-1]['timeMs']<=config['merging']['same_pair_gap_ms']:
                    previous['samples'].append(row)
                else:
                    if previous:
                        finished[method].append(previous)
                    active[method][key] = _episode(row)
            if emit:
                emit(row)
    events = {}
    for method in config['methods']:
        episodes = finished[method]+list(active[method].values())
        events[method] = _events(episodes,method,config)
    return dict(methods=events,statistics=dict(counts),unavailableReasons=dict(unavailable),trackQualityFlags=dict(engine.stats),
                units={'distance':'mean box heights','closingSpeed':'box heights per second, positive means approaching',
                       'relativeVelocityChange':'box heights per second (difference of two relative velocity vectors, not acceleration)'},
                identityLimitation='No identity ground truth; subtle ID switches and camera/depth effects can remain undetected')
