"""인증 정보는 익명 pipe로만 받고 한도가 있는 원본 수신·변환 결과만 보낸다."""
import ctypes
import json
import os
import resource
import signal
import struct
import sys
import urllib.request
import urllib.error

MAX_REQUEST = 65536
MAX_BYTES = 20 * 1024 * 1024


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        raise ValueError("redirect refused")


def configure_limits(parent_pid):
    if sys.platform != "linux":
        raise ValueError("memory limit unavailable")
    # 부모가 SIGKILL로 끝나도 helper는 남지 않는다. 등록 전 종료 경쟁도 검사한다.
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.prctl(1, signal.SIGKILL, 0, 0, 0) != 0 or os.getppid() != parent_pid:
        raise ValueError("parent death boundary unavailable")
    cap = 1536 * 1024 * 1024
    resource.setrlimit(resource.RLIMIT_AS, (cap, cap))
    if resource.getrlimit(resource.RLIMIT_AS) != (cap, cap):
        raise ValueError("memory limit not applied")
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))


def emit(metadata, body=b""):
    encoded = json.dumps(metadata).encode()
    sys.stdout.buffer.write(struct.pack("!I", len(encoded)) + encoded + body)
    sys.stdout.buffer.flush()


def main():
    incoming = sys.stdin.buffer.read(MAX_REQUEST + 1)
    if len(incoming) > MAX_REQUEST:
        raise ValueError("request limit")
    request = json.loads(incoming)
    if sys.platform == "linux":
        configure_limits(request["parent_pid"])
    headers = {"Content-Type": "application/json", "Authorization": "Bearer " + request["token"]}
    http = urllib.request.Request(request["url"], data=json.dumps(request["args"]).encode(),
                                  headers=headers, method="POST")
    with urllib.request.build_opener(NoRedirect()).open(http, timeout=25) as response:
        if request.get("validate"):
            if response.status != 204:
                raise ValueError("inspection revoked")
            emit({"valid": True})
            return
        mime = response.headers.get("Content-Type", "").split(";")[0]
        limit = MAX_BYTES if mime in {"image/gif", "image/webp"} or request["args"].get("overview") else 10 * 1024 * 1024
        length = int(response.headers.get("Content-Length", "0"))
        if mime not in {"image/gif", "image/webp", "image/jpeg", "image/png"} or not 0 < length <= limit:
            raise ValueError("invalid response")
        emit({"phase": "received", "requires_validation": mime in {"image/gif", "image/webp"}
              or bool(request["args"].get("overview"))})
        raw = response.read(limit + 1)
        if len(raw) != length:
            raise ValueError("response length mismatch")
    if mime in {"image/gif", "image/webp"} or request["args"].get("overview"):
        configure_limits(request["parent_pid"])
        from image_conversion import convert, ResultLimit
        try:
            details = {}
            body = convert(raw, mime, request["args"].get("region"), request["args"].get("overview", False), details)
        except ResultLimit as exc:
            emit({"code": "region_required", "display_width": exc.width, "display_height": exc.height,
                  "orientation_applied": True, "frame_index": 0})
            return
        emit({"mime": "image/png", "first_frame": mime in {"image/gif", "image/webp"}, **details}, body)
    else:
        if (mime == "image/png" and not raw.startswith(b"\x89PNG\r\n\x1a\n")) \
                or (mime == "image/jpeg" and not raw.startswith(b"\xff\xd8")):
            raise ValueError("invalid magic")
        emit({"mime": mime, "first_frame": False}, raw)


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as exc:
        try:
            error = json.loads(exc.read(16384))
        except (ValueError, OSError):
            error = {}
        finally:
            exc.close()
        emit({"code": "region_required" if exc.code == 422 and error.get("code") ==
              "ATTACHMENT_INSPECTION_LIMIT" else "original_unavailable"})
    except Exception:
        emit({"code": "original_unavailable"})
