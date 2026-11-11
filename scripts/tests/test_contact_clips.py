import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'ai-service'))
from app.contact_clips import plan_windows, union_intervals


def event(i,start,end,part='P1'):
    return dict(eventId=f'E{i}',matchId='M1',partId=part,segmentId=0,startTimeMs=start,peakTimeMs=(start+end)/2,
                endTimeMs=end,candidateScore=0.5,closingSpeedEvidence={'value':None},relativeVelocityChangeEvidence={'value':None})


class ContactClipTests(unittest.TestCase):
    def test_padding_boundaries_and_overlap_merge_keep_original_event_ids(self):
        events=[event(1,20,30),event(2,45,55)]
        original=copy.deepcopy(events)
        windows=plan_windows(events,0,100,count=2,before_ms=40,after_ms=40)
        self.assertEqual(len(windows),1)
        self.assertEqual(windows[0]['eventIds'],['E1','E2'])
        self.assertEqual(windows[0]['startTimeMs'],0)
        self.assertEqual(windows[0]['endTimeMs'],95)
        self.assertEqual(events,original)
        ending=plan_windows([event(3,85,95)],0,100,count=1,before_ms=40,after_ms=40)
        self.assertEqual(ending[0]['endTimeMs'],100)
        self.assertIn('E3',ending[0]['eventIds'])

    def test_time_dedup_counts_intersection_once_and_never_sums_gaps(self):
        intervals=union_intervals([(10,20),(15,30),(40,50),(50,55)])
        self.assertEqual(intervals,[[10,30],[40,55]])
        self.assertEqual(sum(b-a for a,b in intervals),35)

    def test_missing_segment_or_different_part_is_never_joined(self):
        with self.assertRaisesRegex(ValueError,'One continuous'):
            plan_windows([event(1,20,30),event(2,40,50,'P2')],0,100)
        a,b=event(1,20,30),event(2,40,50)
        b['segmentId']=1
        with self.assertRaisesRegex(ValueError,'One continuous'):
            plan_windows([a,b],0,100)
        with self.assertRaisesRegex(ValueError,'outside'):
            plan_windows([event(1,-10,20)],0,100)


if __name__=='__main__':
    unittest.main()
