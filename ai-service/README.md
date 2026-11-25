# AI Service

## Internal security configuration (WBS 01-03)

Before starting, configure `AI_SERVICE_TOKEN` (random secret, at least 32 characters),
`AI_STORAGE_ROOT` (existing absolute shared storage directory), and `AI_CALLBACK_BASE_URL`
(fixed backend origin with scheme, literal IP and port). Do not commit their values.
Spring must receive the same service credential as `app.ai-service-token`, and the same
callback origin as `app.internal-callback-base-url`. Configure these using the deployment
environment or private configuration; no instance configuration is rewritten by this change.
The old `app.internal-token` is no longer accepted for progress callbacks.

Private HTTP operations require `X-AI-Service-Token`. Analysis also requires an ephemeral,
task-scoped `X-Callback-Token` issued by the backend. Never send either credential in a URL
or a JSON body. Backend-managed callback grants expire after two hours and are revoked
when the synchronous HTTP analysis invocation ends. They are not durable attempt IDs.

Only a fixed `/api/internal/tasks/{taskId}/progress` callback is allowed. DNS origins,
userinfo, query strings, fragments and redirects are rejected; `localhost` configuration
is pinned to `127.0.0.1`. Deployment needs correctly configured origins on both services.
Inputs are restricted to storage `uploads`; outputs to `tasks/task-{taskId}`. Existing
symlinks, reparse points and multiply linked output files are rejected before analysis.
Use filesystem ACLs: these checks do not provide atomic protection from malicious concurrent
filesystem writers. `/health` is public and minimal; Swagger/OpenAPI endpoints are disabled.

The HTTP endpoint accepts explicit `mode: REAL_CONTACT` for the real upload pipeline.
This route requires CUDA and the pinned local YOLOv8n weights (SHA256 checked), then reuses
the P1 detection script, P2 BYTETracker adapter, P3 candidates and P4 PTS clip extractor.
It never calls the legacy analyzer or falls back to mock results. Failures return an error;
zero candidates remain zero. Calls with omitted mode retain the historical MOCK contract;
non-mock detector/tracker environment settings in that legacy route are still rejected.

For REAL_CONTACT, reuse `ai-service/.venv-yolo` or install `requirements-real-contact.txt`
into a dedicated Python 3.11 environment. Place the registered model at
`models/yolo/v8.3.0/yolov8n.pt`; do not download it again when already present.
Configure the three private service variables above and start **one** Uvicorn worker:
`python -m uvicorn app.main:app --host 127.0.0.1 --port 8001` from `ai-service`.
Spring uses corresponding `FRAMECALL_AI_SERVICE_TOKEN`, `FRAMECALL_STORAGE_ROOT`,
`FRAMECALL_CALLBACK_BASE_URL` and `FRAMECALL_AI_SERVICE_URL`; both services must share
the same absolute storage path. Use the existing backend private H2 configuration.
YOLO sync/autoinstall/download should stay disabled (`YOLO_OFFLINE=true`,
`YOLO_AUTOINSTALL=false`, local `YOLO_CONFIG_DIR`).

In Vue, select “真实接触候选（CUDA）” and upload a short real football video. The upload
request stores `analysisMode=REAL_CONTACT` on its task and retains it for retries.
The existing task worker sends `mode=REAL_CONTACT` to FastAPI and accepts only CONTACT
events with real-mode metrics. Progress includes DETECTING, TRACKING, EVENT_ANALYSIS,
CLIPPING and completion; existing cancellation/attempt barriers still apply.
The adapter serializes GPU work within the one service process and checks cancellation
in detection, association, candidate analysis and extraction; it adds no scheduler.

Uploads without confirmed official identity use `UPLOAD-T<taskId>` scope keys in local
track/candidate data; source metadata keeps official matchId/partId null and labels the
timeline UPLOADED_FILE_PTS. Each attempt stores detections.jsonl, tracks.jsonl,
candidates.json, detection/tracking run logs, selected MP4/JPEG media and
analysis_result.json beneath the existing task attempt directory. Source PTS and local
clip offsets remain distinct. All candidates are kept, including ones outside the
small representative set of review clips. Scores are review priorities, never foul
probabilities; COCO person includes possible referees and sideline people.

The optional detector/tracker/pose instructions below describe the **legacy fixtures**,
not the supported real upload route. Do not combine their fallback outputs with
REAL_CONTACT. Targeted adapter checks: `python -B -m unittest discover -s tests
-p test_real_contact_adapter.py` (synthetic contracts, not football ground truth).

Run tests using the existing virtual environment: `python -B -m unittest discover -s tests -v`.
Security tests start isolated Uvicorn and loopback callback fixtures; they do not use real media.

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
