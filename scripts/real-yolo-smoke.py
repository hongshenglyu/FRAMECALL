"""Standalone real CUDA detection. No service callbacks, tracking, or mock fallback."""
from __future__ import annotations

import argparse
import csv
from fractions import Fraction
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parents[1]


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--event-root', type=Path, required=True, help='Local root for the event of the chosen part')
    parser.add_argument('--part-id', required=True)
    parser.add_argument('--start-seconds', type=float, default=0)
    parser.add_argument('--duration-seconds', type=float, default=25)
    parser.add_argument('--imgsz', type=int, choices=[640, 512], default=640)
    parser.add_argument('--conf', type=float, default=0.25)
    parser.add_argument('--run-name', required=True, help='New folder name under ignored artifacts/real-yolo-smoke')
    args = parser.parse_args()
    if args.start_seconds < 0 or not 0 < args.duration_seconds <= 180:
        parser.error('Use a non-negative start and duration in (0, 180] seconds')
    if Path(args.run_name).name != args.run_name or args.run_name in {'.', '..'}:
        parser.error('run-name must be a single new directory name')
    return args


def run(args: argparse.Namespace, out: Path) -> dict:
    import av
    import cv2
    import torch
    from ultralytics import YOLO, settings

    torch.set_num_threads(2)
    cv2.setNumThreads(2)
    settings.update({'sync': False})
    if not torch.cuda.is_available():
        raise RuntimeError('CUDA_UNAVAILABLE: this entry point never substitutes CPU or Mock')
    device = torch.device('cuda:0')
    with (ROOT/'docs/data/video-parts.csv').open(encoding='utf-8-sig', newline='') as f:
        choices = [row for row in csv.DictReader(f) if row['partId'] == args.part_id]
    if len(choices) != 1:
        raise ValueError('PartID must identify exactly one manifest row')
    part = choices[0]
    event_root = args.event_root.resolve(strict=True)
    source = (event_root/part['relativePath']).resolve(strict=True)
    if not source.is_relative_to(event_root):
        raise ValueError('Manifest relative path escapes event root')
    before = (source.stat().st_size, source.stat().st_mtime_ns)
    weights_meta = json.loads((ROOT/'config/yolov8n.weights.json').read_text(encoding='utf8'))
    weights = ROOT/weights_meta['localPath']
    digest = hashlib.sha256(weights.read_bytes()).hexdigest()
    if digest != weights_meta['sha256']:
        raise RuntimeError('WEIGHTS_HASH_MISMATCH')

    started = time.perf_counter()
    model = YOLO(str(weights)).to(device)
    if next(model.model.parameters()).device.type != 'cuda':
        raise RuntimeError('MODEL_NOT_ON_CUDA')
    torch.cuda.reset_peak_memory_stats(0)
    free_before, total_memory = torch.cuda.mem_get_info(0)
    frames = detections = nonempty = 0
    counts = []
    first_time = last_time = None
    last_annotated = None
    inference_seconds = 0.0
    frame_devices = set()
    clip_start = Fraction(str(args.start_seconds))
    clip_end = clip_start + Fraction(str(args.duration_seconds))
    output_tb = Fraction(1, 90000)
    decode_started = time.perf_counter()
    print('MODEL_READY', torch.cuda.get_device_name(0), 'sequential decode preserves original frameIndex', flush=True)

    with av.open(str(source), mode='r') as video, (out/'detections.jsonl').open('w', encoding='utf8') as data, av.open(str(out/'annotated.mp4'), 'w') as rendered:
        stream = video.streams.video[0]
        stream.thread_count = 2
        encoder = rendered.add_stream('libx264', rate=max(1, round(float(stream.average_rate))))
        encoder.width, encoder.height = stream.codec_context.width, stream.codec_context.height
        encoder.pix_fmt = 'yuv420p'
        encoder.time_base = output_tb
        encoder.codec_context.time_base = output_tb
        encoder.codec_context.thread_count = 2
        encoder.options = {'preset': 'ultrafast', 'crf': '23', 'tune': 'zerolatency'}
        source_origin = None
        for index, frame in enumerate(video.decode(stream)):
            if frame.pts is None or frame.time_base is None:
                raise RuntimeError('SOURCE_FRAME_WITHOUT_PTS')
            timestamp = frame.pts*frame.time_base
            if source_origin is None:
                source_origin = timestamp
            relative = timestamp-source_origin
            if relative < clip_start:
                if index % 3000 == 0:
                    print('DECODING_PREFIX', index, round(float(relative), 2), flush=True)
                continue
            if relative >= clip_end:
                break
            if last_time is not None and timestamp <= last_time:
                raise RuntimeError('NON_MONOTONIC_SOURCE_PTS')
            if first_time is None:
                first_time = timestamp
            image = frame.to_ndarray(format='bgr24')
            torch.cuda.synchronize(0)
            infer_start = time.perf_counter()
            with torch.inference_mode():
                result = model.predict(image, device=0, batch=1, imgsz=args.imgsz, conf=args.conf,
                                       classes=[0], verbose=False, save=False)[0]
            torch.cuda.synchronize(0)
            inference_seconds += time.perf_counter()-infer_start
            if result.boxes.data.device.type != 'cuda':
                raise RuntimeError('RESULT_NOT_ON_CUDA')
            frame_devices.add(str(result.boxes.data.device))
            boxes = []
            for box in result.boxes.data.detach().cpu().tolist():
                x1, y1, x2, y2, confidence, class_id = box[:6]
                boxes.append({'classId': int(class_id), 'confidence': confidence,
                              'x1': x1, 'y1': y1, 'x2': x2, 'y2': y2})
            row = {'matchId': part['matchId'], 'partId': part['partId'], 'frameIndex': index,
                   'clipFrameIndex': frames, 'timeMs': float(timestamp*1000),
                   'sourceRelativeTimeMs': float(relative*1000), 'sourcePts': frame.pts,
                   'sourceTimeBase': str(frame.time_base),
                   'outputTimeMs': float((timestamp-first_time)*1000),
                   'width': image.shape[1], 'height': image.shape[0], 'detections': boxes}
            data.write(json.dumps(row, ensure_ascii=False)+'\n')
            annotated = result.plot(line_width=2, labels=False)
            cv2.putText(annotated, f'COCO person | source frame {index} | {float(relative):.3f}s',
                        (20, 34), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 255), 2)
            if frames == 0:
                cv2.imwrite(str(out/'first.jpg'), annotated)
            if last_time is not None and float(last_time-first_time) < args.duration_seconds/2 <= float(timestamp-first_time):
                cv2.imwrite(str(out/'middle.jpg'), annotated)
            last_annotated = annotated
            output_frame = av.VideoFrame.from_ndarray(annotated, format='bgr24')
            output_frame.pts = round((timestamp-first_time)/output_tb)
            output_frame.time_base = output_tb
            for packet in encoder.encode(output_frame):
                rendered.mux(packet)
            last_time = timestamp
            frames += 1
            detections += len(boxes)
            nonempty += bool(boxes)
            counts.append(len(boxes))
            if frames % 100 == 0:
                print('INFERENCE', frames, 'detections', detections,
                      'elapsed', round(time.perf_counter()-decode_started, 2), flush=True)
        for packet in encoder.encode():
            rendered.mux(packet)
        fps_rational = str(stream.average_rate)
    if frames == 0:
        raise RuntimeError('NO_FRAMES_IN_REQUESTED_WINDOW')
    cv2.imwrite(str(out/'last.jpg'), last_annotated)
    if (source.stat().st_size, source.stat().st_mtime_ns) != before:
        raise RuntimeError('SOURCE_CHANGED_DURING_INFERENCE')
    elapsed = time.perf_counter()-started
    return {'status': 'COMPLETED', 'device': 'cuda:0', 'gpu': torch.cuda.get_device_name(0),
            'python': sys.version.split()[0],
            'versions': {p: importlib.metadata.version(p) for p in ['torch','torchvision','ultralytics','av','opencv-python','numpy']},
            'torchCudaBuild': torch.version.cuda, 'resultDevices': sorted(frame_devices),
            'weightsSha256': digest, 'matchId': part['matchId'], 'partId': part['partId'],
            'sourceAlias': part['eventId']+'/'+part['relativePath'], 'sourceStatUnchanged': True,
            'sourceAverageFpsRational': fps_rational, 'requestedStartSeconds': args.start_seconds,
            'requestedDurationSeconds': args.duration_seconds, 'firstTimeMs': float(first_time*1000),
            'lastTimeMs': float(last_time*1000), 'sampledPtsSpanSeconds': float(last_time-first_time),
            'frames': frames, 'personDetections': detections, 'framesWithPersons': nonempty,
            'personsPerFrame': {'min': min(counts), 'max': max(counts), 'mean': detections/frames},
            'imgsz': args.imgsz, 'batch': 1, 'confidenceThreshold': args.conf, 'classes': [0], 'stride': 1,
            'elapsedSecondsIncludingPrefixDecode': elapsed, 'inferenceSeconds': inference_seconds,
            'inferenceFramesPerSecond': frames/inference_seconds,
            'peakAllocatedMiB': torch.cuda.max_memory_allocated(0)/1024**2,
            'peakReservedMiB': torch.cuda.max_memory_reserved(0)/1024**2,
            'gpuFreeMiBAtStart': free_before/1024**2, 'gpuTotalMiB': total_memory/1024**2,
            'outputs': ['annotated.mp4','detections.jsonl','first.jpg','middle.jpg','last.jpg'],
            'meaning': 'COCO person boxes per frame; counts are not unique players; no tracking or foul classification'}


def main() -> int:
    args = arguments()
    os.environ.setdefault('YOLO_CONFIG_DIR', str(ROOT/'artifacts/real-yolo-smoke/settings'))
    Path(os.environ['YOLO_CONFIG_DIR']).mkdir(parents=True, exist_ok=True)
    os.environ.setdefault('YOLO_OFFLINE', 'true')
    os.environ.setdefault('YOLO_AUTOINSTALL', 'false')
    os.environ.setdefault('OMP_NUM_THREADS', '2')
    os.environ.setdefault('MKL_NUM_THREADS', '2')
    out = ROOT/'artifacts/real-yolo-smoke'/args.run_name
    out.mkdir(parents=True, exist_ok=False)
    try:
        result = run(args, out)
    except Exception as exc:
        result = {'status': 'FAILED', 'errorType': type(exc).__name__, 'error': str(exc), 'mockFallback': False}
        (out/'run.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf8')
        print(json.dumps(result, ensure_ascii=False), file=sys.stderr)
        return 1
    (out/'run.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf8')
    print(json.dumps(result, ensure_ascii=False), flush=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
