"""Service identity, fixed callback transport, and task-scoped filesystem boundaries."""
from __future__ import annotations

import contextlib
import contextvars
import hmac
import ipaddress
import os
import re
import stat
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from fastapi import HTTPException

_callback = contextvars.ContextVar("internal_callback", default=None)


def reject(code: str, status: int = 422) -> None:
    raise HTTPException(status_code=status, detail={"code": code})


def service_authenticated(value: str | None) -> bool:
    expected = os.environ.get("AI_SERVICE_TOKEN", "")
    if (not re.fullmatch(r"[A-Za-z0-9_+/=\-]{32,256}", expected) or len(set(expected)) < 12
            or any(word in expected.lower() for word in ("changeme", "change-me", "replace", "placeholder", "example", "default", "password", "demo"))):
        reject("INTERNAL_CONFIGURATION_UNAVAILABLE", 503)
    return bool(value and hmac.compare_digest(value.encode(), expected.encode()))


def fixed_origin(value: str) -> str:
    try:
        url = urllib.parse.urlsplit(value)
        if url.scheme not in {"http", "https"} or url.username or url.password or url.path not in {"", "/"} or url.query or url.fragment:
            raise ValueError()
        host = url.hostname
        if host == "localhost":
            host = "127.0.0.1"  # Explicit pin; never consult DNS or a proxy.
        address = ipaddress.ip_address(host)
        port = url.port or (443 if url.scheme == "https" else 80)
        host = f"[{address}]" if address.version == 6 else str(address)
        return f"{url.scheme}://{host}:{port}"
    except (ValueError, TypeError):
        reject("INTERNAL_CONFIGURATION_UNAVAILABLE", 503)


def callback_target(task_id: int) -> str:
    return fixed_origin(os.environ.get("AI_CALLBACK_BASE_URL", "")) + f"/api/internal/tasks/{task_id}/progress"


def lexical_path(raw: str, root: Path) -> Path:
    if not raw or any(c in raw for c in '%~<>"|?*') or any(ord(c) < 32 or 0xD800 <= ord(c) <= 0xDFFF for c in raw):
        reject("UNSAFE_PATH")
    value = raw.replace("\\", "/")
    if value.startswith("//"):
        reject("UNSAFE_PATH")
    no_drive = value[3:] if len(value) > 2 and value[0].isalpha() and value[1:3] == ":/" else value
    if ":" in no_drive or any(p in {".", ".."} or p.endswith((".", " ")) for p in no_drive.split("/")):
        reject("UNSAFE_PATH")
    path = Path(raw)
    if not path.is_absolute():
        path = root / path
    path = path.absolute()
    if not path.is_relative_to(root):
        reject("UNSAFE_PATH")
    return path


def inspect_path(path: Path, root: Path) -> None:
    current = root
    for segment in (None, *path.relative_to(root).parts):
        if segment is not None:
            current = current / segment
        try:
            attrs = current.lstat()
        except FileNotFoundError:
            continue
        if stat.S_ISLNK(attrs.st_mode) or getattr(attrs, "st_file_attributes", 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT:
            reject("UNSAFE_PATH")
        if stat.S_ISREG(attrs.st_mode) and attrs.st_nlink > 1:
            reject("UNSAFE_PATH")
    if not path.resolve().is_relative_to(root.resolve()):
        reject("UNSAFE_PATH")


def validate_analysis(request, callback_token: str | None) -> tuple[Path, str]:
    # All validation happens before analyzer creates directories or sends a callback.
    raw_root = os.environ.get("AI_STORAGE_ROOT", "")
    root = Path(raw_root)
    if not raw_root or not root.is_absolute() or not root.is_dir():
        reject("INTERNAL_CONFIGURATION_UNAVAILABLE", 503)
    root = root.absolute()
    task_id = request.task_id
    if task_id <= 0:
        reject("INVALID_TASK")
    target = callback_target(task_id)
    if request.callback_url not in (None, target) or request.internal_token is not None:
        reject("UNSAFE_CALLBACK")
    if not callback_token or not re.fullmatch(r"[A-Za-z0-9_-]{32,256}", callback_token):
        reject("CALLBACK_CREDENTIAL_REQUIRED")
    output = lexical_path(request.output_dir, root)
    task_root = root / "tasks" / f"task-{task_id}"
    relative = output.relative_to(task_root) if output.is_relative_to(task_root) else None
    attempt_path = (relative is not None and len(relative.parts) == 2 and relative.parts[0] == "attempts"
                    and re.fullmatch(r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}", relative.parts[1]))
    if output != task_root and not attempt_path:
        reject("UNSAFE_OUTPUT_DIRECTORY")
    video = lexical_path(request.video_path, root)
    if not video.is_relative_to(root / "uploads"):
        reject("UNSAFE_VIDEO_PATH")
    inspect_path(output, root)
    inspect_path(video, root)
    if not video.is_file():
        reject("VIDEO_UNAVAILABLE")
    if output.exists():
        if not output.is_dir():
            reject("UNSAFE_OUTPUT_DIRECTORY")
        for base, directories, files in os.walk(output, followlinks=False):
            for name in directories + files:
                inspect_path(Path(base) / name, root)
    return output, target


@contextlib.contextmanager
def callback_scope(target: str, token: str):
    context = _callback.set((target, token))
    try:
        yield
    finally:
        _callback.reset(context)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def send_progress(request, update) -> None:
    context = _callback.get()
    if context is None:
        if request.callback_url:
            reject("UNBOUND_CALLBACK")
        return  # Existing direct, no-network algorithm fixtures.
    target, token = context
    message = update.model_copy(update={"message": "Analysis progress"})
    outgoing = urllib.request.Request(target, data=message.model_dump_json(by_alias=True).encode(),
                                      headers={"Content-Type": "application/json", "X-Internal-Token": token}, method="POST")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect())
    try:
        with opener.open(outgoing, timeout=2) as response:
            if not 200 <= response.status < 300:
                reject("CALLBACK_REJECTED", 502)
    except (urllib.error.URLError, OSError):
        reject("CALLBACK_UNAVAILABLE", 502)
