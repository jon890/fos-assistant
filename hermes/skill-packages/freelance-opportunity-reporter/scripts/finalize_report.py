#!/usr/bin/env python3
"""외주 공고 자료를 조립·검증하고 로컬 HTML 리포트를 만든다."""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from assemble_report_data import assemble
from audit_collection import audit
from render_report import load_candidates, rank_key, render_document, selected_action, selected_score
from score_opportunities import score


def artifact_paths(runtime_dir: Path, report_date: str) -> dict[str, Path | list[Path]]:
    return {
        "enriched": sorted(runtime_dir.glob(f"*-enriched-{report_date}.json")),
        "combined": runtime_dir / f"freelance-opportunities-{report_date}.json",
        "html": runtime_dir / f"freelance-opportunity-report-{report_date}.html",
    }


def ensure_combined(paths: dict[str, Path | list[Path]], report_date: str) -> Path:
    combined = paths["combined"]
    assert isinstance(combined, Path)
    enriched = paths["enriched"]
    assert isinstance(enriched, list)
    if combined.exists():
        combined_mtime = combined.stat().st_mtime_ns
        if not any(source.stat().st_mtime_ns > combined_mtime for source in enriched):
            return combined

    if not enriched:
        raise ValueError("리포트를 만들 플랫폼별 수집 JSON이 없습니다")
    payload = assemble(
        enriched,
        datetime.now(ZoneInfo("Asia/Seoul")).isoformat(timespec="seconds"),
    )
    combined.parent.mkdir(parents=True, exist_ok=True)
    combined.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return combined


def build_summary(combined: Path) -> dict[str, object]:
    audit_result = audit(json.loads(combined.read_text(encoding="utf-8")))
    if audit_result["missing_fields"] or audit_result["duplicate_keys"]:
        raise ValueError("원자료에 필수 필드 누락 또는 중복 공고가 있습니다")

    candidates = load_candidates(combined)
    if not candidates:
        raise ValueError("원격 조건을 통과한 검토 후보가 없습니다")
    scored = [score(item, remote_only=True) for item in candidates]
    review = [row for row in scored if selected_action(row) != "avoid"]
    apply_now = [row for row in review if selected_action(row) == "apply-now"]
    clarify = [row for row in review if selected_action(row) == "clarify-first"]
    top = sorted(review, key=rank_key, reverse=True)[:3]
    return {
        "audit_status": audit_result["status"],
        "candidate_count": len(review),
        "apply_now_count": len(apply_now),
        "clarify_first_count": len(clarify),
        "top_candidates": [
            {
                "title": row["title"],
                "score": int(selected_score(row)),
                "action": selected_action(row),
            }
            for row in top
        ],
    }


def render_html(combined: Path, html_path: Path, report_date: str) -> None:
    document = render_document(load_candidates(combined), report_date)
    required = ("검토 후보", "플랫폼별 상위 후보", "수집한 공고 목록")
    missing = [label for label in required if label not in document]
    if missing:
        raise ValueError(f"HTML 필수 내용이 없습니다: {', '.join(missing)}")
    html_path.parent.mkdir(parents=True, exist_ok=True)
    html_path.write_text(document, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--date", default=datetime.now(ZoneInfo("Asia/Seoul")).date().isoformat())
    parser.add_argument("--runtime-dir", type=Path, default=Path("freelance-reports"))
    args = parser.parse_args()

    paths = artifact_paths(args.runtime_dir, args.date)
    combined = ensure_combined(paths, args.date)
    summary = build_summary(combined)
    html_path = paths["html"]
    assert isinstance(html_path, Path)
    render_html(combined, html_path, args.date)

    result: dict[str, object] = {
        "status": "ready",
        "report_date": args.date,
        **summary,
        "html": str(html_path),
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
