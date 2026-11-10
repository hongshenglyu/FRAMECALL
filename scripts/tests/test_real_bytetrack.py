"""Adapter business checks with actual BYTETracker; not football identity metrics."""
import importlib.util
import os
from pathlib import Path
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[2]
os.environ['CUDA_VISIBLE_DEVICES'] = '-1'
os.environ['YOLO_AUTOINSTALL'] = 'false'
os.environ['YOLO_OFFLINE'] = 'true'
os.environ['YOLO_CONFIG_DIR'] = str(ROOT/'artifacts/real-yolo-smoke/settings')
spec = importlib.util.spec_from_file_location('real_bytetrack', ROOT/'scripts/real-bytetrack.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
CONFIG = yaml.safe_load((ROOT/'config/bytetrack-p2.yaml').read_text(encoding='utf8'))


def box(x, score):
    return dict(x1=x,y1=20,x2=x+30,y2=100,confidence=score,classId=0)


def frame(index, detections, part='part-A'):
    return dict(matchId='match-A',partId=part,frameIndex=index,clipFrameIndex=index,
                timeMs=index*1000/30,width=640,height=480,detections=detections)


class RealByteTrackAdapterTests(unittest.TestCase):
    def test_low_score_and_short_absence_keep_id_with_correct_detection_mapping(self):
        rows = [frame(0,[box(50,0.8)]),frame(1,[box(300,0.7),box(52,0.15)]),
                frame(2,[]),frame(3,[box(54,0.85)])]
        result, stats = module.associate(rows,CONFIG,1.0)
        first = result[0]['tracks'][0]
        low = next(t for t in result[1]['tracks'] if t['trackId']==first['trackId'])
        self.assertEqual(low['detectionIndex'],1)  # Second-stage subset idx must map to original row.
        self.assertEqual(low['detectionBbox'],[52,20,82,100])
        self.assertEqual(result[2]['tracks'],[])
        self.assertIn(first['trackId'],[t['trackId'] for t in result[3]['tracks']])
        self.assertEqual(stats['lowScoreCandidateObservations'],1)

    def test_discontinuous_source_frames_reset_identity_namespace(self):
        rows = [frame(i,[box(50,0.8)]) for i in [0,1,8,9]]
        result, stats = module.associate(rows,CONFIG,1.0)
        self.assertEqual(result[0]['tracks'][0]['trackId'],result[2]['tracks'][0]['trackId'])
        self.assertNotEqual(result[0]['tracks'][0]['trackKey'],result[2]['tracks'][0]['trackKey'])
        self.assertEqual(len(stats['resetEvents']),1)

    def test_different_parts_cannot_be_silently_stitched(self):
        with self.assertRaisesRegex(ValueError,'One match/part'):
            module.associate([frame(0,[box(50,0.8)]),frame(1,[box(52,0.8)],part='part-B')],CONFIG,1.0)
        a, _ = module.associate([frame(0,[box(50,0.8)]),frame(1,[box(52,0.8)])],CONFIG,1.0)
        b, _ = module.associate([frame(0,[box(50,0.8)],'part-B'),frame(1,[box(52,0.8)],'part-B')],CONFIG,1.0)
        self.assertEqual(a[0]['tracks'][0]['trackId'],b[0]['tracks'][0]['trackId'])
        self.assertNotEqual(a[0]['tracks'][0]['trackKey'],b[0]['tracks'][0]['trackKey'])


if __name__ == '__main__':
    unittest.main()
