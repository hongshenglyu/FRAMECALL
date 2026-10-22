# AI Service

FastAPI service responsible for video analysis. The default mode uses a deterministic mock analyzer that produces the same JSON contract as the future YOLO + ByteTrack + pose pipeline.

Run locally:

```powershell
cd ai-service
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
.\.venv\Scripts\python -m uvicorn app.main:app --reload --port 8001
```

Health check:

```powershell
Invoke-RestMethod http://localhost:8001/health
```

Run the contract unit tests:

```powershell
cd ai-service
.\.venv\Scripts\python -m unittest discover -s tests
```

## Optional YOLO detector and tracking

The service now has guarded YOLO and tracking entry points. They are off by default so the full demo remains stable without model weights.

```powershell
cd ai-service
.\.venv\Scripts\python -m pip install -r requirements-yolo.txt
$env:AI_ANALYZER_MODE = "yolo"
$env:AI_YOLO_MODEL = "models\yolo\yolov8n.pt"
$env:AI_TRACKER_MODE = "bytetrack"
.\.venv\Scripts\python -m uvicorn app.main:app --reload --port 8001
```

Useful environment variables:

- `AI_ANALYZER_MODE`: `mock` or `yolo`; default is `mock`
- `AI_YOLO_MODEL`: local `.pt` weights path; default is `models/yolo/yolov8n.pt`
- `AI_YOLO_ALLOW_DOWNLOAD`: set `true` only when you want Ultralytics to download a plain weight name such as `yolov8n.pt`
- `AI_YOLO_CONF`: detection confidence threshold; default is `0.25`
- `AI_YOLO_MAX_FRAMES`: sampled frames per task; default is `12`
- `AI_YOLO_STRIDE`: frame stride for sampling; default is `15`
- `AI_YOLO_CLASSES`: comma-separated labels to keep; default is `person,sports ball`
- `AI_TRACKER_MODE`: `mock` or `bytetrack`; default is `mock`
- `AI_FOUL_RULE_MODE`: `track-rule-v1` or `mock`; default is `track-rule-v1`
- `AI_FOUL_TRACK_PAIRS`: optional semicolon-separated track pairs such as `7,12;9,3`
- `AI_FOUL_DISTANCE_THRESHOLD_PX`: proximity threshold for track-rule scoring; default is `140`
- `AI_FOUL_VELOCITY_DELTA_PX_S`: relative velocity-change threshold; default is `30`
- `AI_FOUL_CLOSING_SPEED_PX_S`: closing-speed threshold; default is `40`
- `AI_POSE_MODE`: `local-rule-v1`, `mock`, or `disabled`; default is `local-rule-v1`
- `AI_POSE_LIMB_DISTANCE_PX`: local crop distance scale for pose proxy scoring; default is `120`

When YOLO is enabled, the AI result writes detector metadata into `metrics.detector` and, when detection succeeds, saves `detections/detections.json` plus a preview image under the task output directory.

Tracking metadata is written to `metrics.tracking`. Every task also writes `tracks/tracks.json` and, when Pillow is available, `tracks/tracks_preview.png`. In `mock` mode these tracks are deterministic demo tracks. In `bytetrack` mode, completed YOLO detections are normalized into the tracking contract, but real ByteTrack association still needs to replace the adapter.

FOUL candidates now use the first rule scorer, `track-rule-v1`, which combines minimum player-pair distance, relative velocity change, and closing speed. Its output is written to `metrics.foulCandidate` and `rules/foul_candidates.json`.

Local pose features run only on the selected FOUL candidate pair. The result is written to `metrics.poseEstimation`, `pose/pose_features.json`, and `pose/pose_preview.png`. The current `local-rule-v1` estimator is a lightweight local-crop feature proxy; replace it with a real pose model once the detector and tracker are stable. HANDBALL and OFFSIDE remain deterministic candidate hints until their rule scorers are added.
