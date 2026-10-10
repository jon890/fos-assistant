"""현재 실행의 첨부 원본을 CP에서 받아 같은 모델의 native 이미지로 돌려준다."""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request

from .context import build_context, _read_token, signing_key, logger, top_level_session

TOOL = "attachment_inspect"
MAX_BYTES = 10 * 1024 * 1024
TIMEOUT = 30
FAILURE = "원본 사진을 받지 못해 판독하지 못했다. 재업로드를 요구하지 말고 실패를 알린다."


def failure(message=FAILURE, code="original_unavailable"):
    return json.dumps({"error": message, "code": code}, ensure_ascii=False)
SCHEMA = {
    "name": TOOL,
    "description": "같은 대화의 사진 원본을 다시 본다. 작은 글자·가격·품번은 자동 조회한다. "
                   "큰 사진은 표시 원본 좌표 [x1,y1,x2,y2] 영역으로 조회한다. 한 번에 한 장씩 본다.",
    "parameters": {
        "type": "object", "additionalProperties": False,
        "properties": {
            "attachment_id": {"type": "integer", "minimum": 1},
            "region": {"type": "array", "items": {"type": "integer", "minimum": 0},
                       "minItems": 4, "maxItems": 4,
                       "description": "EXIF 표시 원본 픽셀 좌표. 오른쪽·아래 끝 제외. 생략하면 전체 원본."},
        },
        "required": ["attachment_id"],
    },
}


def valid_args(args):
    if not isinstance(args, dict) or set(args) - {"attachment_id", "region", "_fos_ctx", "_fos_inspect"}:
        return False
    attachment = args.get("attachment_id")
    if type(attachment) is not int or not 0 < attachment <= 2**63 - 1:
        return False
    region = args.get("region")
    return region is None or (isinstance(region, list) and len(region) == 4
        and all(type(n) is int and 0 <= n <= 2**31 - 1 for n in region)
        and region[2] > region[0] and region[3] > region[1])


def digest(args):
    region = args.get("region") or []
    text = str(args["attachment_id"]) + "\n" + ",".join(str(n) for n in region)
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def sign_request(key, ctx, issued, request_digest, top_level):
    text = "\n".join(["v1-attachment-inspect", ctx["root_session_id"], ctx["session_id"],
                      ctx["tool_call_id"], str(issued), request_digest, "1" if top_level else "0"])
    return hmac.new(key.encode("utf-8"), text.encode("utf-8"), hashlib.sha256).hexdigest()


def authorize(args, session_id, tool_call_id, isolated=False):
    if isolated or not valid_args(args):
        return {"action": "block", "message": FAILURE}
    try:
        ctx = build_context(TOOL, session_id, tool_call_id)
        token = _read_token()
        if not ctx or not token:
            return {"action": "block", "message": FAILURE}
        issued = time.time_ns() // 1_000_000
        top_level = top_level_session(session_id)
        if top_level is None:
            return {"action": "block", "message": FAILURE}
        proof = {"issued_at_ms": issued, "top_level": top_level,
                 "sig": sign_request(signing_key(token), ctx, issued, digest(args), top_level)}
        return {"action": "modify", "args": {"_fos_ctx": ctx, "_fos_inspect": proof}}
    except Exception as exc:
        logger.warning("fos-ctx: 원본 조회 서명 실패: %s", type(exc).__name__)
        return {"action": "block", "message": FAILURE}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        raise urllib.error.HTTPError(request.full_url, code, "redirect refused", headers, response)


def _open(request):
    return urllib.request.build_opener(NoRedirect()).open(request, timeout=TIMEOUT)


def handle(args, **_):
    if not valid_args(args) or not isinstance(args.get("_fos_ctx"), dict) \
            or not isinstance(args.get("_fos_inspect"), dict):
        return failure()
    try:
        url = os.environ.get("FOS_ATTACHMENT_INSPECT_URL", "")
        parsed = urllib.parse.urlsplit(url)
        token = _read_token()
        if not token or parsed.scheme not in {"http", "https"} or not parsed.netloc \
                or parsed.path != "/internal/hermes/attachment-inspect" or parsed.query or parsed.fragment:
            return failure()
        request = urllib.request.Request(url, data=json.dumps(args).encode("utf-8"), method="POST",
            headers={"Content-Type": "application/json", "Authorization": "Bearer " + token})
        deadline = time.monotonic() + TIMEOUT
        with _open(request) as response:
            mime = response.headers.get("Content-Type", "").split(";")[0]
            length = int(response.headers.get("Content-Length", "0"))
            if mime not in {"image/jpeg", "image/png"} or not 0 < length <= MAX_BYTES:
                return failure()
            chunks, size = [], 0
            while size <= MAX_BYTES:
                if time.monotonic() > deadline:
                    return failure()
                chunk = response.read(min(65536, MAX_BYTES + 1 - size))
                if not chunk:
                    break
                chunks.append(chunk)
                size += len(chunk)
            body = b"".join(chunks)
            if size != length or size > MAX_BYTES:
                return failure()
            if (mime == "image/png" and not body.startswith(b"\x89PNG\r\n\x1a\n")) \
                    or (mime == "image/jpeg" and not body.startswith(b"\xff\xd8")):
                return failure()
        notice = "첨부 참조 " + str(args["attachment_id"]) + "의 원본" + (
            " 영역 " + str(args["region"]) if args.get("region") else " 전체")
        return {"_multimodal": True, "content": [
            {"type": "text", "text": notice + ". 아래 native 사진을 직접 보고 판독한다."},
            {"type": "image_url", "image_url": {
                "url": "data:" + mime + ";base64," + base64.b64encode(body).decode("ascii"),
                "detail": "original"}},
        ], "text_summary": "첨부 원본 조회 결과. 이 글만 있고 native 사진이 없으면 원본을 보았다고 하거나 "
                           "글자를 추측하지 말고 판독 실패를 알린다."}
    except urllib.error.HTTPError as exc:
        try:
            error = json.loads(exc.read(16384))
        except (ValueError, OSError):
            error = {}
        finally:
            exc.close()
        if exc.code == 422 and error.get("code") == "ATTACHMENT_INSPECTION_LIMIT":
            return failure("전체 원본의 안전 한도를 넘었다. 작은 영역을 지정해 한 번만 다시 조회한다. "
                           "계속 실패하면 판독하지 못했다고 알린다.", "region_required")
        return failure()
    except Exception as exc:
        logger.warning("fos-ctx: 원본 조회 실패: %s", type(exc).__name__)
        return failure()
