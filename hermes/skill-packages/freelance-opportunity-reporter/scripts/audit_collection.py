#!/usr/bin/env python3
"""외주 공고 원자료의 누락, 중복, 필수 필드를 검사한다."""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path


REQUIRED_KEYS = (
    "platform",
    "project_id",
    "title",
    "url",
    "registered_at",
    "collected_at",
    "list_page",
    "collection_source",
    "detail_status",
    "eligibility_status",
    "exclusion_reason",
)
NON_EMPTY_FIELDS = (
    "platform",
    "project_id",
    "title",
    "url",
    "collected_at",
    "collection_source",
    "detail_status",
    "eligibility_status",
)


def audit(payload: dict | list) -> dict:
    if isinstance(payload, list):
        items = payload
        collection = []
    elif isinstance(payload, dict):
        items = payload.get("items", [])
        collection = payload.get("collection", [])
    else:
        raise ValueError("원자료는 공고 배열이거나 items 배열을 가진 객체여야 합니다.")

    missing_fields: list[dict] = []
    keys: list[tuple[str, str]] = []
    for index, item in enumerate(items):
        missing = [
            field
            for field in REQUIRED_KEYS
            if field not in item
            or (field in NON_EMPTY_FIELDS and not item.get(field))
        ]
        if missing:
            missing_fields.append({"index": index, "fields": missing})

        platform = str(item.get("platform", ""))
        project_id = str(item.get("project_id", ""))
        if platform and project_id:
            keys.append((platform, project_id))

    source_keys: dict[str, set[tuple[str, str]]] = {}
    for item in items:
        source_id = str(item.get("collection_source", ""))
        platform = str(item.get("platform", ""))
        project_id = str(item.get("project_id", ""))
        if source_id and platform and project_id:
            source_keys.setdefault(source_id, set()).add((platform, project_id))

    duplicate_keys = [
        {"platform": platform, "project_id": project_id, "count": count}
        for (platform, project_id), count in Counter(keys).items()
        if count > 1
    ]

    coverage: list[dict] = []
    covered_sources: set[str] = set()
    for source in collection:
        source_id = str(source.get("source_id", ""))
        platform = str(source.get("platform", ""))
        if source_id:
            covered_sources.add(source_id)
        advertised = source.get("advertised_count")
        coverage_expected = source.get("coverage_expected", True)
        collected = len(source_keys.get(source_id, set()))
        missing_count = (
            max(0, int(advertised) - collected)
            if coverage_expected and advertised is not None
            else None
        )
        if not coverage_expected:
            coverage_status = "not-applicable"
        elif missing_count is None:
            coverage_status = "unchecked"
        elif missing_count == 0:
            coverage_status = "complete"
        else:
            coverage_status = "incomplete"
        coverage.append(
            {
                "source_id": source_id,
                "platform": platform,
                "advertised_count": advertised,
                "collected_count": collected,
                "missing_count": missing_count,
                "coverage_status": coverage_status,
            }
        )

    untracked_sources = sorted(set(source_keys) - covered_sources)
    incomplete_coverage = (
        not coverage
        or any(row["coverage_status"] == "unchecked" for row in coverage)
        or any(row["coverage_status"] == "incomplete" for row in coverage)
        or bool(untracked_sources)
    )
    status = (
        "incomplete"
        if missing_fields or duplicate_keys or incomplete_coverage
        else "complete"
    )
    return {
        "status": status,
        "item_count": len(items),
        "unique_item_count": len(set(keys)),
        "missing_fields": missing_fields,
        "duplicate_keys": duplicate_keys,
        "coverage": coverage,
        "untracked_sources": untracked_sources,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("--pretty", action="store_true")
    parser.add_argument(
        "--strict",
        action="store_true",
        help="검사 결과가 incomplete이면 종료 코드 1을 반환합니다.",
    )
    args = parser.parse_args()

    payload = json.loads(args.input.read_text(encoding="utf-8"))
    result = audit(payload)
    print(json.dumps(result, ensure_ascii=False, indent=2 if args.pretty else None))
    if args.strict and result["status"] != "complete":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
