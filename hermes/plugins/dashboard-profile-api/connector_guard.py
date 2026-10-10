"""보호 선언과 바인딩 지원을 검증한다. 금융 도구의 exclude는 이 단계에서 풀지 않는다."""

from __future__ import annotations

import hashlib
import json
import os
import re
import urllib.request
import urllib.error
import uuid

PROTOCOL = "approval-claim-v1"
EXECUTION_ENV = frozenset({"FOS_APPROVAL_TICKET", "FOS_APPROVAL_ARGS_JSON", "FOS_APPROVAL_CLAIM_URL"})
GUARD_KEYS = {"v", "protocol", "bindingId", "connectionId", "connectionUpdatedAt", "manifestSha256"}
RESERVED = set("orderId symbol market currency side quantity orderAmount price orderType timeInForce status "
               "filledQuantity execution clientOrderId expected_order confirmHighValueOrder userId connectionId tool".split())
HEX = re.compile(r"[0-9a-f]{64}")
IDENTIFIER = re.compile(r"[A-Za-z][A-Za-z0-9_]{0,63}")
REVISION = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3}(?:\d{3})?)?Z")


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("중복 JSON 키다")
        result[key] = value
    return result


def _strict_json(raw):
    return json.loads(raw, object_pairs_hook=_unique_object,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError("JSON 숫자가 아니다")))


async def _guard_request_body(request):
    # 실제 HTTP 본문은 tree 변환 전에 검증한다. 기존 시험 요청은 json()만 제공한다.
    if hasattr(request, "body"):
        try:
            raw = await request.body()
            if len(raw) > 32 * 1024:
                return None
            body = _strict_json(raw)
        except (ValueError, UnicodeError):
            return None
    else:
        body = await request.json()
    return body if isinstance(body, dict) else None


def _guard_manifest(manifest, declared):
    if set(manifest["server"]["env"]) & EXECUTION_ENV:
        raise ValueError("실행 전용 env 는 설치에 선언하지 않는다")
    guard = declared.get("execution_guard")
    manifest["execution_guard"] = None
    manifest["execution_guard_manifest_sha256"] = None
    if guard is None:
        return manifest
    if (declared["schema"] != 2 or not isinstance(guard, dict)
            or set(guard) != {"protocol", "prepare_tool", "scope_fields", "operations"}
            or guard["protocol"] != PROTOCOL):
        raise ValueError("지원하지 않는 보호 선언이다")
    policies = manifest["tools"]
    prepare = guard["prepare_tool"]
    if not isinstance(prepare, str) or policies.get(prepare, {}).get("risk") != "READ" or policies[prepare]["approval"] != "none":
        raise ValueError("prepare_tool 은 READ/none 도구다")
    operations = guard["operations"]
    if (not isinstance(operations, dict) or not 1 <= len(operations) <= 8
            or any(not isinstance(name, str) or policies.get(name, {}).get("risk") != "FINANCIAL"
                   or policies[name]["approval"] != "always" or operation not in {"CREATE", "MODIFY", "CANCEL"}
                   for name, operation in operations.items())):
        raise ValueError("operations 는 FINANCIAL/always 도구의 동작이다")
    fields = guard["scope_fields"]
    public = {field["key"] for field in manifest["fields"] if not field.get("secret", False)}
    args, names = set(), set()
    if not isinstance(fields, list) or not 1 <= len(fields) <= 8:
        raise ValueError("scope_fields 는 1~8개다")
    for field in fields:
        if (not isinstance(field, dict) or set(field) != {"arg", "field"}
                or any(not isinstance(value, str) or not IDENTIFIER.fullmatch(value) for value in field.values())
                or field["arg"] in RESERVED or field["arg"] in args or field["field"] in names or field["field"] not in public):
            raise ValueError("scope_fields 는 중복 없는 공개 칸이다")
        args.add(field["arg"])
        names.add(field["field"])
    manifest["execution_guard"] = guard
    manifest["execution_guard_manifest_sha256"] = hashlib.sha256(
        json.dumps(declared, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")).hexdigest()
    return manifest


def _binding_guard(guard):
    if (not isinstance(guard, dict) or set(guard) != GUARD_KEYS or type(guard["v"]) is not int
            or guard["v"] != 1 or guard["protocol"] != PROTOCOL
            or not isinstance(guard["manifestSha256"], str) or not HEX.fullmatch(guard["manifestSha256"])
            or not isinstance(guard["connectionUpdatedAt"], str) or not REVISION.fullmatch(guard["connectionUpdatedAt"])):
        raise ValueError("바인딩 보호 맥락이 틀리다")
    _guard_instant(guard["connectionUpdatedAt"])
    for key in ("bindingId", "connectionId"):
        if (not isinstance(guard[key], str) or not re.fullmatch(r"[1-9][0-9]{0,18}", guard[key])
                or int(guard[key]) > 9223372036854775807):
            raise ValueError("바인딩 번호는 양의 Long 문자열이다")
    return dict(guard)


def _guard_instant(raw):
    from datetime import datetime
    value = datetime.fromisoformat(raw.replace("Z", "+00:00"))
    canonical = value.isoformat(timespec="seconds").split("+")[0]
    if value.microsecond:
        canonical += ".%03d" % (value.microsecond // 1000) if value.microsecond % 1000 == 0 else ".%06d" % value.microsecond
    if raw != canonical + "Z":
        raise ValueError("MICROS canonical Instant가 아니다")
    return value


def _guard_hide_financial(profile_dir, state):
    """소유한 서버의 금융 숨김만 복구한다. 현재 설치 기록은 바꾸지 않아 재설치가 필요하다."""
    import stat
    import yaml
    from .common import _atomic_private_write
    from .connector_manifest import _connector_manifest
    config_file = profile_dir / "config.yaml"
    if not config_file.is_file():
        return
    if config_file.is_symlink():
        raise ValueError("profile 설정이 링크다")
    original = config_file.read_bytes()
    mode = stat.S_IMODE(config_file.stat().st_mode)
    config = yaml.safe_load(original) or {}
    servers = config.get("mcp_servers", {})
    changed = False
    for connector, entry in state.items():
        manifest = _connector_manifest(connector)
        if manifest is None:
            continue
        server = servers.get(entry.get("mcp_server"))
        if not isinstance(server, dict) or server != entry["server"]:
            continue
        required = {name for name, policy in manifest["tools"].items() if policy["risk"] == "FINANCIAL"}
        excluded = server.get("tools", {}).get("exclude", [])
        if required - set(excluded):
            server["tools"] = {"exclude": sorted(set(excluded) | required)}
            changed = True
    if changed:
        if config_file.is_symlink() or config_file.read_bytes() != original:
            raise ValueError("profile 설정이 확인 중 바뀌었다")
        _atomic_private_write(config_file, yaml.safe_dump(config, sort_keys=False, allow_unicode=True).encode("utf-8"))
        os.chmod(config_file, mode)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def _guard_endpoint(suffix):
    # 주소와 호스트 비밀은 고정 운영 환경만 결정한다. manifest와 요청은 지정하지 못한다.
    base = os.environ.get("FOS_CONNECTOR_EXECUTIONS_BASE_URL", "").rstrip("/")
    from urllib.parse import urlsplit
    parsed = urlsplit(base)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.query or parsed.fragment:
        return None
    return base + "/internal/connector-executions/" + suffix


def _guard_support(profile, manifest, entry):
    guard = entry.get("guard") if isinstance(entry, dict) and entry.get("mode") == "bind" else None
    result = {"protocol": PROTOCOL, "state": "unsupported"}
    if not manifest or not manifest.get("execution_guard") or guard is None:
        return result
    try:
        guard = _binding_guard(guard)
        result.update({key: value for key, value in guard.items() if key != "v"})
        result["state"] = "pending"
        if guard["manifestSha256"] != manifest["execution_guard_manifest_sha256"]:
            return result
        url = _guard_endpoint("support")
        secret = os.environ.get("HERMES_DASHBOARD_PROFILE_API_SECRET", "")
        if not url or not secret:
            return result
        expected = {**guard, "nonce": str(uuid.uuid4())}
        body = {**expected, "profile": profile, "connectorId": manifest["id"]}
        request = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"), method="POST",
                                         headers={"Authorization": "Bearer " + secret, "Content-Type": "application/json"})
        # 한 요청, 1초 제한, redirect·재시도 없음. nonce와 성공 원문은 호출 스택에서만 산다.
        with urllib.request.build_opener(_NoRedirect()).open(request, timeout=1) as response:
            raw = response.read(4097)
            if response.status != 200 or len(raw) > 4096:
                return result
        answer = _strict_json(raw)
        if (answer == expected and type(answer.get("v")) is int
                and all(type(answer[key]) is type(value) for key, value in expected.items())):
            result["state"] = "verified"
    except urllib.error.HTTPError as error:
        error.close()
    except Exception:
        # 응답·예외·권한 원문은 로그에 남기지 않는다. 실패는 금융 차단 상태다.
        pass
    return result
