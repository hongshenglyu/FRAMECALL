from __future__ import annotations

import json
import os
import secrets
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

import uvicorn

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.main import app, _cancelled_tasks


class InternalSecurityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sock = socket.socket(); cls.sock.bind(("127.0.0.1", 0))
        cls.origin = f"http://127.0.0.1:{cls.sock.getsockname()[1]}"
        cls.server = uvicorn.Server(uvicorn.Config(app, log_level="critical", access_log=False, lifespan="off"))
        cls.thread = threading.Thread(target=lambda: cls.server.run(sockets=[cls.sock]), daemon=True); cls.thread.start()
        for _ in range(100):
            if cls.server.started: return
            time.sleep(.02)
        raise RuntimeError("Isolated Uvicorn did not start")

    @classmethod
    def tearDownClass(cls):
        cls.server.should_exit = True; cls.thread.join(5); cls.sock.close()

    def setUp(self):
        self.work = tempfile.TemporaryDirectory(prefix="framecall-internal-01-03-")
        self.root = Path(self.work.name) / "storage"
        (self.root / "uploads").mkdir(parents=True); (self.root / "tasks").mkdir()
        self.video = self.root / "uploads/source.mp4"; self.video.write_bytes(b"synthetic-mock-input")
        self.calls = []; self.trap_calls = []; self.redirect = False
        test = self
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                test.calls.append((self.path, self.headers.get("X-Internal-Token"), json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))))
                self.send_response(302 if test.redirect else 200)
                if test.redirect: self.send_header("Location", test.trap_origin + "/sensitive")
                self.end_headers(); self.wfile.write(b'{}')
            def log_message(self, *args): pass
        class Trap(BaseHTTPRequestHandler):
            def do_GET(self):
                test.trap_calls.append(self.path); self.send_response(200); self.end_headers()
            do_POST = do_GET
            def log_message(self, *args): pass
        self.callback = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.trap = ThreadingHTTPServer(("127.0.0.1", 0), Trap)
        self.callback_origin = f"http://127.0.0.1:{self.callback.server_port}"
        self.trap_origin = f"http://127.0.0.1:{self.trap.server_port}"
        for server in (self.callback, self.trap): threading.Thread(target=server.serve_forever, daemon=True).start()
        self.service_token = secrets.token_urlsafe(40); self.callback_token = secrets.token_urlsafe(32)
        self.environment = patch.dict(os.environ, AI_SERVICE_TOKEN=self.service_token, AI_STORAGE_ROOT=str(self.root),
                AI_CALLBACK_BASE_URL=self.callback_origin, AI_ANALYZER_MODE="mock", AI_TRACKER_MODE="mock", AI_YOLO_ALLOW_DOWNLOAD="false")
        self.environment.start(); _cancelled_tasks.clear()

    def tearDown(self):
        self.environment.stop()
        for server in (self.callback, self.trap): server.shutdown(); server.server_close()
        self.work.cleanup()

    def payload(self, **changes):
        return dict(taskId=7, videoPath=str(self.video), outputDir=str(self.root / "tasks/task-7"), sensitivity="BALANCED",
                    enabledEvents=["FOUL"], callbackUrl=self.callback_origin + "/api/internal/tasks/7/progress") | changes

    def request(self, path="/internal/analyze", body=None, *, auth=True, headers=None, method="POST", expected=200):
        outgoing = {"Content-Type": "application/json"}
        if auth: outgoing |= {"X-AI-Service-Token": self.service_token, "X-Callback-Token": self.callback_token}
        outgoing |= headers or {}
        req = urllib.request.Request(self.origin + path, data=None if method == "GET" else json.dumps(body if body is not None else self.payload()).encode(), headers=outgoing, method=method)
        try:
            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=10) as response:
                status, content = response.status, response.read()
        except urllib.error.HTTPError as error: status, content = error.code, error.read()
        evidence = os.environ.get("WBS03_AI_EVIDENCE")
        if evidence:
            with Path(evidence).open("a", encoding="utf-8") as file:
                file.write(json.dumps(dict(case=self._testMethodName, endpoint=path, expected=expected, actual=status,
                    outcome="PASS" if expected == status else "FAIL", callbacks=len(self.calls), trapRequests=len(self.trap_calls))) + "\n")
        self.assertEqual(status, expected)
        if status >= 400:
            for value in [self.service_token, self.callback_token, str(self.root)]: self.assertNotIn(value.encode(), content)
        return json.loads(content)

    def assert_no_effects(self):
        self.assertEqual(self.calls, []); self.assertEqual(self.trap_calls, [])
        self.assertEqual(list((self.root / "tasks").iterdir()), []); self.assertEqual(_cancelled_tasks, set())

    def test_private_operations_reject_anonymous_wrong_service_bearer_and_cookie(self):
        for path, method in [("/internal/analyze", "POST"), ("/internal/tasks/7/cancel", "POST"), ("/docs", "GET"), ("/openapi.json", "GET"), ("/redoc", "GET")]:
            for headers in [{}, {"X-AI-Service-Token": "wrong"}, {"Authorization": "Bearer synthetic-user-jwt"}, {"Cookie": "framecall_media=synthetic-user-jwt"}]:
                self.request(path, auth=False, headers=headers, method=method, expected=401)
        self.assert_no_effects()

    def test_health_is_minimal_and_documentation_disabled(self):
        self.assertEqual(self.request("/health", method="GET", auth=False), {"status": "ok", "service": "ai-service"})
        for path in ["/docs", "/redoc", "/openapi.json"]: self.request(path, method="GET", expected=404)

    def test_valid_service_runs_actual_mock_analyzer_and_fixed_callback(self):
        result = self.request()
        self.assertEqual(result["status"], "SUCCEEDED"); self.assertEqual(result["metrics"]["detector"]["status"], "disabled")
        self.assertTrue((self.root / "tasks/task-7/analysis_result.json").is_file()); self.assertGreater(len(self.calls), 0)
        self.assertTrue(all(path == "/api/internal/tasks/7/progress" and token == self.callback_token for path, token, body in self.calls))
        self.assertTrue(all(body["message"] == "Analysis progress" for path, token, body in self.calls))
        self.assertNotIn(self.callback_token, json.dumps(result))

    def test_callback_override_protocol_port_encoding_userinfo_and_redirect_paths_fail_before_network(self):
        target = self.callback_origin + "/api/internal/tasks/7/progress"
        for url in ["file:///synthetic", "gopher://127.0.0.1:1/", self.trap_origin + "/sensitive", self.callback_origin + "/redirect",
                    target + "?x=1", target + "#fragment", target.replace("/7/", "/8/"), target.replace("/api/", "/%61pi/"),
                    target.replace("127.0.0.1", "localhost"), target.replace("http:", "https:"), target.replace("//", "//user@", 1),
                    "http://rebind.test:8080/api/internal/tasks/7/progress"]:
            self.request(body=self.payload(callbackUrl=url), expected=422)
        self.request(body=self.payload(internalToken="body-secret"), expected=422); self.assert_no_effects()

    def test_callback_redirect_is_not_followed(self):
        self.redirect = True; self.request(expected=502)
        self.assertEqual(len(self.calls), 1); self.assertEqual(self.trap_calls, [])
        self.assertFalse((self.root / "tasks/task-7/analysis_result.json").exists())

    def test_dns_name_configuration_rejected_without_resolution(self):
        with patch.dict(os.environ, AI_CALLBACK_BASE_URL="http://rebind.test:8080"), patch("socket.getaddrinfo", side_effect=AssertionError("DNS must not run")) as resolver:
            from app.security import callback_target
            from fastapi import HTTPException
            with self.assertRaises(HTTPException) as failure: callback_target(7)
            self.assertEqual(failure.exception.status_code, 503); resolver.assert_not_called()
        self.assert_no_effects()

    def test_output_traversal_encoded_unc_drive_prefix_and_cross_task_rejected(self):
        for value in [str(Path(self.work.name) / "outside"), str(Path(self.work.name) / "storage-lookalike/tasks/task-7"),
                      "../outside", "tasks/../tasks/task-7", "tasks/%2e%2e/task-7", "tasks/%252e%252e/task-7", "\\\\server\\share\\task-7",
                      "Z:\\outside\\task-7", "tasks/task-8", "tasks/task-7.", "tasks/task-7 ", "tasks/task-7:stream", "tasks/TASK~1", "tasks/a\0", "tasks/task-7/child"]:
            self.request(body=self.payload(outputDir=value), expected=422)
        self.assert_no_effects()

    def test_video_cannot_read_outside_uploads(self):
        for value in [str(Path(self.work.name) / "secret.mp4"), "../uploads/source.mp4", "uploads/%2e%2e/source.mp4", "tasks/task-7/result.mp4",
                      "uploads/a?.mp4", "uploads/a|b.mp4", "uploads/a\nb.mp4"]:
            self.request(body=self.payload(videoPath=value), expected=422)
        self.assert_no_effects()

    def test_attempt_output_keeps_previous_evidence_and_strict_task_boundary(self):
        import uuid
        first = self.root / 'tasks/task-7/attempts' / str(uuid.uuid4())
        second = self.root / 'tasks/task-7/attempts' / str(uuid.uuid4())
        self.request(body=self.payload(outputDir=str(first)))
        original = (first / 'analysis_result.json').read_bytes()
        self.request(body=self.payload(outputDir=str(second)))
        self.assertEqual((first / 'analysis_result.json').read_bytes(), original)
        self.assertTrue((second / 'analysis_result.json').is_file())
        count = len(self.calls)
        for value in ['tasks/task-7/attempts/not-a-uuid', 'tasks/task-7/attempts/' + str(uuid.uuid4()) + '/child',
                      'tasks/task-8/attempts/' + str(uuid.uuid4()), 'tasks/task-7/attempts/../other']:
            self.request(body=self.payload(outputDir=value), expected=422)
        self.assertEqual(len(self.calls), count)

    def test_nested_junction_rejected_before_callback_or_overwrite(self):
        if os.name != "nt": self.skipTest("Windows junction fixture")
        target = Path(self.work.name) / "outside"; target.mkdir()
        marker = target / "marker"; marker.write_bytes(b"unchanged")
        output = self.root / "tasks/task-7"; output.mkdir(); junction = output / "events"
        result = subprocess.run(["cmd", "/c", "mklink", "/J", str(junction), str(target)], capture_output=True)
        self.assertEqual(result.returncode, 0, "junction creation failed")
        try:
            self.request(expected=422); self.assertEqual(self.calls, []); self.assertEqual(marker.read_bytes(), b"unchanged")
        finally: os.rmdir(junction)

    def test_symbolic_link_rejected_when_creation_permitted(self):
        target = Path(self.work.name) / "outside"; target.mkdir(); link = self.root / "tasks/task-7"
        try: link.symlink_to(target, target_is_directory=True)
        except OSError: self.skipTest("Windows did not grant symbolic-link creation; junction separately tested")
        try:
            self.request(expected=422); self.assertEqual(self.calls, []); self.assertEqual(list(target.iterdir()), [])
        finally: link.unlink()

    def test_existing_result_hardlink_cannot_overwrite_other_file(self):
        target = Path(self.work.name) / "outside.json"; target.write_bytes(b"unchanged")
        output = self.root / "tasks/task-7"; output.mkdir(); os.link(target, output / "analysis_result.json")
        self.request(expected=422); self.assertEqual(target.read_bytes(), b"unchanged"); self.assertEqual(self.calls, [])

    def test_relative_output_and_windows_case_alias(self):
        self.request(body=self.payload(outputDir="tasks/task-7"))
        if os.name == "nt": self.request(body=self.payload(outputDir=str(self.root / "tasks/task-7").upper()))

    def test_missing_config_callback_credentials_and_bad_json_fail_closed(self):
        for value in ["", "0" * 64, "CHANGE_ME_EXAMPLE_SERVICE_CREDENTIAL_0123456789"]:
            with patch.dict(os.environ, AI_SERVICE_TOKEN=value): self.request(expected=503)
        self.request(headers={"X-Callback-Token": ""}, expected=422)
        self.assertEqual(self.request(body=self.payload(taskId={"private": "no-reflection"}), expected=422), {"code": "INVALID_REQUEST"})
        self.assert_no_effects()

    def test_non_mock_fails_before_models_or_network(self):
        with patch.dict(os.environ, AI_ANALYZER_MODE="yolo", AI_YOLO_MODEL="missing-private-weight.pt"), patch("app.detector._run_yolo") as detector:
            self.request(expected=503); detector.assert_not_called()
        self.assert_no_effects()

    def test_authenticated_cancel_preserves_shape(self):
        self.assertEqual(self.request("/internal/tasks/7/cancel", body={}), {"taskId": 7, "status": "CANCEL_REQUESTED"})
        self.assertEqual(_cancelled_tasks, {7})
