"""본문 snapshot의 선언과 private process 경계를 검증한다."""

from .connector_schema import TOOL_NAME_RE

SNAPSHOT_FLAG = "--content-snapshot-operation"
SNAPSHOT_ENV = frozenset({"FOS_CONTENT_SNAPSHOT_OPERATION", "FOS_CONTENT_SAVE_TICKET",
                          "FOS_CONTENT_SAVE_AUTHORIZE_URL"})
SNAPSHOT_CAPABILITIES = {"compile": True, "start": False, "status": False}


def _snapshot_manifest(declared, server, tools, env_names):
    # private mode는 dashboard가 자식을 띄울 때만 붙인다. 공개 설치 정의로는 켜지 못한다.
    if (set(env_names) & SNAPSHOT_ENV
            or any(arg.startswith(SNAPSHOT_FLAG) for arg in server["args"])):
        raise ValueError("private snapshot 인자와 env는 설치에 선언하지 않는다")
    value = declared.get("snapshot")
    if "snapshot" not in declared:
        return None
    keys = {"protocol", "compile_tool", "start_tool", "status_tool", "capabilities"}
    if not isinstance(value, dict) or set(value) != keys or value["protocol"] != "content-snapshot-v1":
        raise ValueError("snapshot 선언의 키와 protocol이 올바르지 않다")
    names = [value[key] for key in ("compile_tool", "start_tool", "status_tool")]
    if (any(not isinstance(name, str) or not TOOL_NAME_RE.fullmatch(name) for name in names)
            or len(set(names)) != 3 or set(names) & set(tools)):
        raise ValueError("snapshot 도구 이름은 공개 도구와 겹치지 않는다")
    caps = value["capabilities"]
    if (not isinstance(caps, dict) or set(caps) != set(SNAPSHOT_CAPABILITIES)
            or any(type(item) is not bool for item in caps.values()) or caps["compile"] is not True
            or caps["start"] != caps["status"]):
        raise ValueError("snapshot capability가 올바르지 않다")
    return {**value, "capabilities": dict(caps)}
