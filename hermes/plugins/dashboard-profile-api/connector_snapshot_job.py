"""예약 job의 strict 입력과 본문 없는 결과만 전달한다."""

import hashlib
import os
import re
from datetime import datetime
from urllib.parse import urlsplit

STAMP_KEYS = {"protocol", "snapshotId", "jobId", "snapshotHash", "bindingId", "connectionId",
              "bindingUpdatedAt", "connectionUpdatedAt", "profileHash"}
STAGES = {"queued", "open", "fill", "photos", "components", "settings", "save", "state"}
STATUSES = {"running", "succeeded", "failed", "unknown"}


def _matches(value, pattern):
    return isinstance(value, str) and re.fullmatch(pattern, value) is not None


def _job_args(profile, operation, args):
    keys = {"stamp"} if operation == "status" else {"snapshot", "jobId", "ticket", "stamp"}
    if not isinstance(args, dict) or set(args) != keys:
        raise ValueError("job 입력이 올바르지 않다")
    stamp = args["stamp"]
    if not isinstance(stamp, dict) or set(stamp) != STAMP_KEYS or stamp["protocol"] != "content-snapshot-v1":
        raise ValueError("job stamp가 올바르지 않다")
    for name in ("snapshotId", "jobId"):
        if not _matches(stamp[name], r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"):
            raise ValueError("job ID가 올바르지 않다")
    for name in ("snapshotHash", "profileHash"):
        if not _matches(stamp[name], r"[0-9a-f]{64}"):
            raise ValueError("job hash가 올바르지 않다")
    for name in ("bindingId", "connectionId"):
        if (not _matches(stamp[name], r"[1-9][0-9]{0,18}")
                or int(stamp[name]) > 9223372036854775807):
            raise ValueError("job 바인딩이 올바르지 않다")
    for name in ("bindingUpdatedAt", "connectionUpdatedAt"):
        if not _matches(stamp[name], r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z"):
            raise ValueError("job 시각이 올바르지 않다")
        datetime.fromisoformat(stamp[name].replace("Z", "+00:00"))
    if not isinstance(profile, str) or stamp["profileHash"] != hashlib.sha256(profile.encode("utf-8")).hexdigest():
        raise ValueError("job profile이 다르다")
    if operation == "start":
        if (args["jobId"] != stamp["jobId"] or not _matches(args["ticket"], r"[\x21-\x7e]{1,4096}")
                or not isinstance(args["snapshot"], dict)
                or args["snapshot"].get("snapshotHash") != stamp["snapshotHash"]):
            raise ValueError("job snapshot이 다르다")


def _authorize_url():
    # 호출 인자·manifest·profile은 주소를 정하지 못한다. 기존 CP base의 접두사만 보존한다.
    base = os.environ.get("FOS_CONNECTOR_EXECUTIONS_BASE_URL", "").rstrip("/")
    parsed = urlsplit(base)
    if (parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username
            or parsed.password or parsed.query or parsed.fragment):
        raise ValueError("authorize 주소를 확인하지 못했다")
    return base + "/internal/content-draft-jobs/authorize"


def _job_result(operation, args, result):
    if set(result) == {"error"} and result["error"] in {"invalid_input", "not_found", "start_unknown", "unavailable"}:
        return result
    keys = {"job_id", "status", "stamp", "error_code"}
    if operation == "status":
        keys |= {"stage", "save_clicked", "finished_at", "saved_before", "saved_after"}
    if (set(result) != keys or result["stamp"] != args["stamp"] or result["job_id"] != args["stamp"]["jobId"]
            or result["status"] not in STATUSES
            or result["error_code"] is not None and not _matches(result["error_code"], r"[a-z][a-z_]{0,63}")):
        raise ValueError("job 결과를 확인하지 못했다")
    if operation == "status":
        if (result["stage"] is not None and result["stage"] not in STAGES
                or type(result["save_clicked"]) is not bool):
            raise ValueError("job 단계가 올바르지 않다")
        if result["finished_at"] is not None:
            if not _matches(result["finished_at"], r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z"):
                raise ValueError("job 종료 시각이 올바르지 않다")
            datetime.fromisoformat(result["finished_at"].replace("Z", "+00:00"))
        for name in ("saved_before", "saved_after"):
            value = result[name]
            if value is not None and (result["status"] != "succeeded" or type(value) is not int or not 0 <= value <= 9007199254740991):
                raise ValueError("job 결과 수치가 올바르지 않다")
    return result
