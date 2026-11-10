"""Local motion evidence and review-event boundaries, not football accuracy metrics."""
import copy
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'ai-service'))
from app.contact_candidates import analyze_frames

CONFIG = json.loads((ROOT/'config/contact-candidates.json').read_text(encoding='utf8'))


def track(key, x, confidence=0.9):
    return dict(trackKey=key,trackId=int(key[-1]),classId=0,confidence=confidence,x1=x-10,y1=100,x2=x+10,y2=200)


def frame(index, time_ms, distance_px=40, present=True, part='P1', segment=0, offset=0):
    return dict(matchId='M',partId=part,segmentId=segment,frameIndex=index,timeMs=time_ms,
                sourcePts=round(time_ms),sourceTimeBase='1/1000',
                tracks=[track('T1',100+offset),track('T2',100+offset+distance_px)] if present else [])


def calculate(rows, config=None):
    timeline = []
    result = analyze_frames(rows,config or CONFIG,timeline.append)
    return result,timeline


class ContactCandidateTests(unittest.TestCase):
    def test_real_dt_supports_approach_not_fixed_fps(self):
        times = [0,27,67,105,141,180,217,253,289,325,362,401]
        result,rows = calculate([frame(i,t,60-0.05*t) for i,t in enumerate(times)])
        last = rows[-1]
        self.assertAlmostEqual(last['closingSpeedHeightPerSecond'],0.5,places=7)
        self.assertAlmostEqual(last['relativeVelocityChangeHeightPerSecond'],0,places=7)
        self.assertEqual(last['motionSupport']['samples'],12)
        self.assertTrue(result['methods']['combined'])

    def test_short_or_low_confidence_history_keeps_distance_without_made_up_motion(self):
        result,rows = calculate([frame(0,0),frame(1,34)])
        self.assertTrue(result['methods']['distance-baseline'])
        self.assertIsNone(rows[-1]['closingSpeedHeightPerSecond'])
        self.assertEqual(rows[-1]['closingUnavailableReason'],'INSUFFICIENT_CONTIGUOUS_SAMPLES')
        low = [frame(i,i*34) for i in range(12)]
        for row in low:
            row['tracks'][1]['confidence'] = 0.15
        result,rows = calculate(low)
        self.assertTrue(result['methods']['distance-baseline'])
        self.assertTrue(all(r['closingSpeedHeightPerSecond'] is None for r in rows))
        self.assertIn('LOW_CONFIDENCE_FOR_MOTION',rows[-1]['qualityFlags'])

    def test_relative_velocity_change_measures_a_real_local_turn(self):
        rows = [frame(i,t,45-0.03*t if t<=280 else 36.6+0.02*(t-280)) for i,t in enumerate(range(0,601,40))]
        _,timeline = calculate(rows)
        self.assertAlmostEqual(timeline[-1]['relativeVelocityChangeHeightPerSecond'],0.5,places=7)

    def test_missing_frame_retains_distance_but_never_interpolates_velocity(self):
        rows = [frame(i,i*34) for i in range(14)]
        rows[8]['tracks'] = []
        result,timeline = calculate(rows)
        returned = next(r for r in timeline if r['frameIndex']==9)
        self.assertIsNone(returned['closingSpeedHeightPerSecond'])
        self.assertIn('OBSERVATION_GAP',returned['qualityFlags'])
        events = result['methods']['distance-baseline']
        self.assertEqual(len(events),1)
        self.assertEqual(len(events[0]['pairEpisodes'][0]['evidenceIntervals']),2)
        self.assertIn('EVIDENCE_GAPS_NOT_INTERPOLATED',events[0]['qualityFlags'])
        self.assertTrue(all(not (s['startTimeMs']<272<s['endTimeMs']) for p in events[0]['trackParticipants'] for s in p['localMotionSupportRanges']))

    def test_part_or_segment_id_reset_never_shares_motion_or_events(self):
        rows = [frame(i,i*34) for i in range(10)]
        rows += [frame(i,i*34,segment=1) for i in range(10)]
        rows += [frame(i,i*34,part='P2') for i in range(10)]
        result,timeline = calculate(rows)
        for part,segment in [('P1',0),('P1',1),('P2',0)]:
            first = next(r for r in timeline if r['partId']==part and r['segmentId']==segment)
            self.assertIsNone(first['closingSpeedHeightPerSecond'])
        self.assertEqual(len(result['methods']['distance-baseline']),3)

    def test_large_id_position_jump_starts_new_motion_support(self):
        rows = [frame(i,i*34,offset=100 if i>=8 else 0) for i in range(14)]
        _,timeline = calculate(rows)
        self.assertIn('SUSPECTED_ID_OR_POSITION_JUMP',timeline[8]['qualityFlags'])
        self.assertIsNone(timeline[8]['relativeVelocityChangeHeightPerSecond'])
        self.assertIsNone(timeline[8]['closingSpeedHeightPerSecond'])
        self.assertGreaterEqual(timeline[-1]['motionSupport']['startTimeMs'],272)

    def test_pts_gap_breaks_velocity_even_with_consecutive_frame_numbers(self):
        rows = [frame(i,i*34+(400 if i>=8 else 0)) for i in range(14)]
        result,timeline = calculate(rows)
        self.assertIsNone(timeline[8]['closingSpeedHeightPerSecond'])
        self.assertIn('OBSERVATION_GAP',timeline[8]['qualityFlags'])
        event = result['methods']['distance-baseline'][0]
        self.assertEqual(len(event['pairEpisodes'][0]['evidenceIntervals']),2)
        self.assertGreaterEqual(timeline[-1]['motionSupport']['startTimeMs'],672)

    def test_same_contest_merges_brief_distance_gaps_but_not_separate_contests(self):
        rows = [frame(i,i*50,40 if i<5 or 8<=i<13 or i>=32 else 200) for i in range(38)]
        result,_ = calculate(rows)
        events = result['methods']['distance-baseline']
        self.assertEqual(len(events),2)
        self.assertEqual((events[0]['startTimeMs'],events[0]['endTimeMs']),(0,600))
        self.assertEqual(len(events[0]['pairEpisodes'][0]['evidenceIntervals']),2)

    def test_combination_switches_and_empty_case_are_honest(self):
        config = copy.deepcopy(CONFIG)
        config['methods']['combined'] = dict(config['methods']['distance-baseline'])
        _,rows = calculate([frame(i,i*34) for i in range(12)],config)
        self.assertTrue(all(r['scores']['distance-baseline']==r['scores']['combined'] for r in rows))
        result,_ = calculate([frame(i,i*34,400) for i in range(12)])
        self.assertTrue(all(not events for events in result['methods'].values()))
        invalid = [frame(0,100),frame(1,99)]
        with self.assertRaisesRegex(ValueError,'Non-monotonic'):
            calculate(invalid)

    def test_overlapping_pair_evidence_shares_event_not_physical_identity(self):
        rows = [frame(i,i*50) for i in range(20)]
        for row in rows[2:10]:
            row['tracks'].append(track('T3',120))
        result,_ = calculate(rows)
        events = result['methods']['distance-baseline']
        self.assertEqual(len(events),1)
        self.assertEqual(len(events[0]['trackParticipants']),3)
        self.assertIn('SHARED_ANCHOR_EVENT_MERGE_NOT_IDENTITY_STITCH',events[0]['qualityFlags'])
        self.assertTrue(all(not s['identityVerified'] for p in events[0]['trackParticipants'] for s in p['localMotionSupportRanges']))


if __name__=='__main__':
    unittest.main()
