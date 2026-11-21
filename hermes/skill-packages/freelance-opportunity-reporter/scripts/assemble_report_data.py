#!/usr/bin/env python3
"""플랫폼별 수집 JSON을 감사 가능한 단일 원자료로 조립한다."""

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


def assemble(paths: list[Path], collected_at: str) -> dict:
    collection = []
    items = []
    for path in paths:
        rows = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(rows, list):
            raise ValueError(f"플랫폼 수집 파일은 JSON 배열이어야 합니다: {path}")
        if not rows:
            continue
        platform = str(rows[0]["platform"])
        if any(str(row.get("platform")) != platform for row in rows):
            raise ValueError(f"한 파일에는 한 플랫폼만 있어야 합니다: {path}")
        current_source_id = source_id(platform)
        collection.append(
            {
                "platform": platform,
                "source_id": current_source_id,
                "advertised_count": len(rows),
                "coverage_expected": True,
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
