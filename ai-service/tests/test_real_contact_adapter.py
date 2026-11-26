"""Isolated adapter contracts; fixtures never become football labels or model evidence."""
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest import TestCase
from unittest.mock import patch
from fractions import Fraction
from fastapi import HTTPException
from app.schemas import AnalysisRequest
from app import real_contact, main
from app import security
from app.schemas import ProgressUpdate
import urllib.error


class AdapterContracts(TestCase):
    def test_review_windows_include_both_sides_of_a_track_reset(self):
        tracks = [{'segmentId': segment, 'timeMs': time_ms}
                  for segment, times in [(0, [0, 1000]), (1, [2500, 3500])] for time_ms in times]
        events = [
            dict(matchId='SYNTHETIC', partId='LOCAL', segmentId=segment, eventId=f'event-{segment}',
                 startTimeMs=peak-100, peakTimeMs=peak, endTimeMs=peak+100, candidateScore=0.5,
                 closingSpeedEvidence={'value': None}, relativeVelocityChangeEvidence={'value': None})
            for segment, peak in [(0, 500), (1, 3000)]
        ]
        windows = real_contact.plan_review_windows(events, tracks, {'count': 1})
        self.assertEqual([(w['startTimeMs'], w['endTimeMs'], w['eventIds']) for w in windows],
                         [(0, 1000, ['event-0']), (2500, 3500, ['event-1'])])
        self.assertEqual(len({w['clipId'] for w in windows}), 2)

    def test_callback_http_rejection_is_distinct_from_transport_failure(self):
        with TemporaryDirectory() as directory, patch.object(security.urllib.request,'build_opener') as opener:
            opener.return_value.open.side_effect=urllib.error.HTTPError('http://127.0.0.1:1',400,'rejected',None,None)
            with security.callback_scope('http://127.0.0.1:1','synthetic'), self.assertRaises(HTTPException) as error:
                security.send_progress(self.request(directory),ProgressUpdate(taskId=123,stage='CLIPPING',progressPercent=82,message='fixture'))
            self.assertEqual(error.exception.detail['code'],'CALLBACK_REJECTED')

    def request(self, directory):
        return AnalysisRequest(taskId=123,videoPath=str(Path(directory)/'synthetic.mp4'),outputDir=directory,mode='REAL_CONTACT')

    def test_cancelled_real_job_never_starts_detector(self):
        with TemporaryDirectory() as directory, patch.object(real_contact,'execute') as execute:
            with self.assertRaises(real_contact.AnalysisCancelled):
                real_contact.run_real_contact(self.request(directory),lambda _:True)
            execute.assert_not_called()

    def test_real_failure_never_invokes_legacy_analyzer(self):
        with TemporaryDirectory() as directory, patch.object(main,'validate_analysis',return_value=(Path(directory),'http://127.0.0.1:1')), \
                patch.object(main,'run_real_contact',side_effect=RuntimeError('CUDA_UNAVAILABLE')), patch.object(main,'run_analysis') as legacy:
            with self.assertRaises(HTTPException) as error:
                main.analyze(self.request(directory),SimpleNamespace(headers={'X-Callback-Token':'synthetic'}))
            self.assertEqual(error.exception.detail['code'],'CUDA_UNAVAILABLE')
            legacy.assert_not_called()
            self.assertFalse(json.loads((Path(directory)/'failure.json').read_text())['mockFallback'])

    def test_zero_candidates_stays_zero_without_placeholder_media(self):
        with TemporaryDirectory() as directory:
            rows=[dict(matchId='SYNTHETIC',partId='LOCAL',segmentId=0,frameIndex=i,timeMs=i*40,sourcePts=i,
                       sourceTimeBase='1/25',width=10,height=10,detections=[]) for i in range(3)]
            def detect(args,out):
                (out/'detections.jsonl').write_text('\n'.join(json.dumps(r) for r in rows))
                return {'fixture':True}
            def associate(rows,*args,**kwargs):
                return [dict(r,tracks=[]) for r in rows],{'fixture':True}
            video=SimpleNamespace(streams=SimpleNamespace(video=[SimpleNamespace(duration=3,time_base=Fraction(1,25))]))
            from contextlib import nullcontext
            with patch('av.open',return_value=nullcontext(video)), patch.object(real_contact,'send_progress'), \
                 patch.object(real_contact,'script',side_effect=lambda name:SimpleNamespace(run=detect,associate=associate)), \
                 patch.object(real_contact,'extract_windows') as cut:
                result=real_contact.execute(self.request(directory),lambda:None)
            self.assertEqual(result.events,[])
            self.assertEqual(result.metrics['clips'],0)
            self.assertEqual(result.metrics['originalCandidates'],0)
            cut.assert_not_called()
