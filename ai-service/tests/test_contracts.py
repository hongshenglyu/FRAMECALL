from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.analyzer import run_analysis
from app.detector import DetectorRun, run_detector
from app.event_rules import score_foul_candidates
from app.pose import analyze_foul_pose
from app.schemas import AnalysisRequest
from app.tracker import run_tracker


class EnvPatch:
    def __init__(self, **values: str) -> None:
        self.values = values
        self.previous: dict[str, str | None] = {}

    def __enter__(self) -> None:
        for key, value in self.values.items():
            self.previous[key] = os.environ.get(key)
            os.environ[key] = value

    def __exit__(self, *_: object) -> None:
        for key, value in self.previous.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


class AiContractTests(unittest.TestCase):
    def test_run_analysis_writes_default_mock_contract(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            output_dir = Path(tmp) / "task-1"
            request = AnalysisRequest(
                taskId=1,
                videoPath=str(Path(tmp) / "demo.mp4"),
                outputDir=str(output_dir),
                sensitivity="BALANCED",
                enabledEvents=["FOUL", "HANDBALL", "OFFSIDE"],
            )

            result = run_analysis(request)

            self.assertEqual(result.status, "SUCCEEDED")
            self.assertEqual(result.stage, "COMPLETED")
            self.assertEqual({event.event_type.value for event in result.events}, {"FOUL", "HANDBALL", "OFFSIDE"})
            self.assertTrue((output_dir / "analysis_result.json").exists())
            self.assertTrue((output_dir / "tracks" / "tracks.json").exists())
            self.assertTrue((output_dir / "rules" / "foul_candidates.json").exists())
            self.assertTrue((output_dir / "pose" / "pose_features.json").exists())

            payload = json.loads((output_dir / "analysis_result.json").read_text(encoding="utf-8"))
            self.assertEqual(payload["taskId"], 1)
            self.assertEqual(payload["metrics"]["detector"]["status"], "disabled")
            self.assertEqual(payload["metrics"]["tracking"]["status"], "completed")
            self.assertEqual(payload["metrics"]["foulCandidate"]["status"], "completed")
            self.assertEqual(payload["metrics"]["poseEstimation"]["status"], "completed")
            self.assertGreaterEqual(payload["events"][0]["confidence"], 0.0)
            self.assertLessEqual(payload["events"][0]["confidence"], 1.0)

    def test_yolo_without_weights_falls_back_without_breaking_contract(self) -> None:
        with tempfile.TemporaryDirectory() as tmp, EnvPatch(AI_ANALYZER_MODE="yolo", AI_YOLO_MODEL=str(Path(tmp) / "missing.pt")):
            video_path = Path(tmp) / "video.mp4"
            video_path.write_bytes(b"not-a-real-video")

            detector = run_detector(video_path, Path(tmp) / "detections")

            self.assertEqual(detector.mode, "yolo")
            self.assertEqual(detector.status, "fallback")
            self.assertIsNone(detector.detections_path)
            self.assertIn("weights", detector.note.lower())

    def test_bytetrack_mode_falls_back_to_mock_tracks_when_detector_is_unavailable(self) -> None:
        detector = DetectorRun(
            mode="yolo",
            status="fallback",
            model="missing.pt",
            sampled_frames=0,
            detection_count=0,
            fps=0.0,
            detections_path=None,
            preview_path=None,
            note="missing weights",
        )

        with tempfile.TemporaryDirectory() as tmp, EnvPatch(AI_TRACKER_MODE="bytetrack"):
            tracking = run_tracker(detector, Path(tmp) / "tracks")

            self.assertEqual(tracking.mode, "bytetrack")
            self.assertEqual(tracking.status, "fallback")
            self.assertEqual(tracking.track_count, 5)
            self.assertTrue(Path(tracking.tracks_path or "").exists())

    def test_foul_rule_and_pose_outputs_are_selected_pair_scoped(self) -> None:
        detector = DetectorRun(
            mode="mock",
            status="disabled",
            model="mock-yolo-small",
            sampled_frames=0,
            detection_count=0,
            fps=0.0,
            detections_path=None,
            preview_path=None,
            note="mock",
        )

        with tempfile.TemporaryDirectory() as tmp:
            tracking = run_tracker(detector, Path(tmp) / "tracks")
            foul = score_foul_candidates(tracking, Path(tmp) / "rules")
            pose = analyze_foul_pose(foul, tracking, Path(tmp) / "pose")

            self.assertEqual(foul.status, "completed")
            self.assertIsNotNone(foul.selected)
            self.assertTrue(Path(foul.rules_path or "").exists())
            self.assertEqual(pose.status, "completed")
            self.assertEqual(pose.scope, "selected_foul_candidate_pair")
            self.assertEqual(pose.selected_track_ids, list(foul.selected.track_ids))
            self.assertTrue(Path(pose.features_path or "").exists())


if __name__ == "__main__":
    unittest.main()
