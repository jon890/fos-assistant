#!/usr/bin/env python3
"""플랫폼별 수집 JSON을 감사 가능한 단일 원자료로 조립한다.

플랫폼별 파일은 두 모양을 받는다.
- 객체: {"platform", "items", 선택 칸 COLLECTION_FIELDS}. 플랫폼이 표시한 전체 건수와 접근 상태를 원자료까지 남긴다.
  접근에 실패한 플랫폼은 items 를 비우고 access_status 에 까닭을 적어도 원자료에 남는다.
- 배열: 공고 목록만 있는 옛 모양. 표시 건수를 모르므로 advertised_count 를 비워 검사가 「미확인」 으로 본다.
"""

from __future__ import annotations

import argparse
import json
from datetime import datetime
from pathlib import Path


def source_id(platform: str) -> str:
    aliases = {
        "위시켓": "wishket",
        "프리모아": "freemoa",
        "원티드 긱스": "wanted-gigs",
    }
    return f"{aliases.get(platform, platform.lower().replace(' ', '-'))}-active-scope"


def eligibility(item: dict) -> tuple[str, str | None]:
    if item.get("remote_only_pass") is False:
        return "excluded", "완전 원격 조건을 충족하지 않음"
    if item.get("remote_only_pass") is not True:
        return "excluded", "완전 원격 조건을 확인하지 못함"
    if float(item.get("fit", 0)) < 2:
        return "excluded", "코딩 외주 또는 사용자 경험 범위와 맞지 않음"
    return "candidate", None


# 객체 모양 파일에서 원자료의 collection 항목으로 그대로 옮기는 칸이다.
COLLECTION_FIELDS = ("advertised_count", "coverage_expected", "access_status", "pages_scanned", "stopped_after_no_new_ids", "coverage_note")


def read_platform_file(path: Path) -> tuple[str | None, list[dict], dict]:
    """플랫폼 이름, 공고 목록, collection 에 옮길 칸을 읽는다."""
    payload = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(payload, dict):
        rows = payload.get("items", [])
        if not isinstance(rows, list):
            raise ValueError(f"플랫폼 수집 파일의 items 는 JSON 배열이어야 합니다: {path}")
        platform = payload.get("platform") or (rows[0].get("platform") if rows else None)
        if not platform:
            raise ValueError(f"플랫폼 수집 파일에 platform 이 없습니다: {path}")
        meta = {key: payload[key] for key in COLLECTION_FIELDS if key in payload}
        return str(platform), rows, meta
    if isinstance(payload, list):
        platform = str(payload[0]["platform"]) if payload else None
        return platform, payload, {"advertised_count": None}
    raise ValueError(f"플랫폼 수집 파일은 JSON 배열이나 객체여야 합니다: {path}")


def assemble(paths: list[Path], collected_at: str) -> dict:
    collection = []
    items = []
    for path in paths:
        platform, rows, meta = read_platform_file(path)
        if platform is None:
            continue
        if any(str(row.get("platform")) != platform for row in rows):
            raise ValueError(f"한 파일에는 한 플랫폼만 있어야 합니다: {path}")
        current_source_id = source_id(platform)
        collection.append(
            {
                "platform": platform,
                "source_id": current_source_id,
                "advertised_count": None,
                "coverage_expected": True,
                **meta,
            }
        )
        for row in rows:
            status, reason = eligibility(row)
            items.append(
                {
                    **row,
                    "collected_at": collected_at,
                    "collection_source": current_source_id,
                    "eligibility_status": status,
                    "exclusion_reason": reason,
                }
            )
    return {"collection": collection, "items": items}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("inputs", nargs="+", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--collected-at", default=datetime.now().astimezone().isoformat(timespec="seconds"))
    args = parser.parse_args()

    payload = assemble(args.inputs, args.collected_at)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(args.output), "item_count": len(payload["items"])}, ensure_ascii=False))


if __name__ == "__main__":
    main()
