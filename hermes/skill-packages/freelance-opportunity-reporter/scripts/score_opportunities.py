#!/usr/bin/env python3
"""JSON 외주 공고 목록에 일관된 점수를 매긴다."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def clamp(value: float, low: float = 0, high: float = 100) -> float:
    return max(low, min(high, value))


def budget_score(budget_krw: int | float | None) -> float:
    if not budget_krw:
        return 8
    if budget_krw < 1_000_000:
        return 10
    if budget_krw < 3_000_000:
        return 16
    if budget_krw < 10_000_000:
        return 22
    if budget_krw < 30_000_000:
        return 24
    return 18


def competition_score(applicants: int | None) -> float:
    if applicants is None:
        return 10
    if applicants == 0:
        return 18
    if applicants <= 3:
        return 16
    if applicants <= 10:
        return 12
    if applicants <= 25:
        return 7
    return 3


def duration_score(days: int | None) -> float:
    if not days:
        return 6
    if days <= 21:
        return 12
    if days <= 60:
        return 10
    if days <= 120:
        return 7
    return 3


def first_win_duration_score(days: int | None) -> float:
    if not days:
        return 0
    if days <= 7:
        return 10
    if days <= 14:
        return 8
    return 0


def first_win_competition_score(applicants: int | None) -> float:
    if applicants is None:
        return 5
    if applicants <= 3:
        return 10
    if applicants <= 10:
        return 8
    if applicants <= 25:
        return 4
    return 1


def is_first_win_candidate(item: dict) -> bool:
    if item.get("first_win_candidate") is False:
        return False

    days = item.get("duration_days")
    budget = item.get("budget_krw")
    fit = float(item.get("fit", 3))
    risk = float(item.get("risk", 3))
    budget_eligible = budget is None or budget > 0
    return bool(days and days <= 14 and budget_eligible and fit >= 3 and risk <= 3)


def first_win_result(item: dict, remote_only: bool) -> tuple[float, str]:
    fit = float(item.get("fit", 3))
    risk = float(item.get("risk", 3))
    scope_clarity = float(item.get("scope_clarity", 3))
    delivery_confidence = float(item.get("delivery_confidence", fit))
    experience_match = float(item.get("experience_match", fit))
    reputation_value = float(item.get("reputation_value", 3))

    total = (
        scope_clarity * 5
        + delivery_confidence * 5
        + experience_match * 4
        + reputation_value * 2
        + first_win_duration_score(item.get("duration_days"))
        + first_win_competition_score(item.get("applicants"))
        - max(0, risk - 1) * 6
    )
    total = round(clamp(total), 1)

    remote_only_pass = item.get("remote_only_pass")
    remote = float(item.get("remote", 3))
    remote_blocked = remote_only_pass is False or remote <= 1
    budget_unknown = item.get("budget_krw") is None

    if remote_only and remote_blocked:
        return min(total, 49), "avoid"
    if remote_only and remote_only_pass is None:
        return total, "clarify-first" if total >= 65 else "avoid"
    if budget_unknown:
        return total, "clarify-first" if total >= 65 else "avoid"
    if total >= 80:
        return total, "apply-now"
    if total >= 65:
        return total, "clarify-first"
    return total, "avoid"


def score(item: dict, remote_only: bool = False) -> dict:
    fit = float(item.get("fit", 3))
    risk = float(item.get("risk", 3))
    portfolio = float(item.get("portfolio", 3))
    remote = float(item.get("remote", 3))

    total = (
        budget_score(item.get("budget_krw"))
        + competition_score(item.get("applicants"))
        + duration_score(item.get("duration_days"))
        + fit * 7
        + portfolio * 4
        + remote * 2
        - risk * 6
    )
    total = round(clamp(total), 1)

    remote_only_pass = item.get("remote_only_pass")
    remote_blocked = remote_only_pass is False or remote <= 1

    if remote_only and remote_blocked:
        total = min(total, 49)
        action = "avoid"
    elif remote_only and remote_only_pass is None and total >= 80:
        action = "clarify-first"
    elif total >= 80:
        action = "apply-now"
    elif total >= 65:
        action = "clarify-first"
    elif total >= 50:
        action = "clarify-first"
    else:
        action = "avoid"

    result = {**item, "score": total, "action": action}
    if is_first_win_candidate(item):
        first_win_score, first_win_action = first_win_result(item, remote_only)
        result["first_win_score"] = first_win_score
        result["first_win_action"] = first_win_action
        if first_win_score >= 65 and first_win_action != "avoid":
            result["recommended_track"] = "first-win"
        else:
            result["recommended_track"] = "standard"
    else:
        result["recommended_track"] = "standard"
    return result


def load_items(path: Path) -> list[dict]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(payload, list):
        return payload
    if isinstance(payload, dict) and isinstance(payload.get("items"), list):
        return payload["items"]
    raise ValueError("입력 JSON은 공고 배열이거나 items 배열을 가진 객체여야 합니다.")


def first_win_sort_key(row: dict) -> tuple[bool, float, float]:
    viable_first_win = (
        row.get("recommended_track") == "first-win"
        and row.get("first_win_action") != "avoid"
    )
    track_score = row.get("first_win_score", -1) if viable_first_win else row["score"]
    return viable_first_win, track_score, row["score"]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("--pretty", action="store_true")
    parser.add_argument("--remote-only", action="store_true")
    parser.add_argument(
        "--first-win",
        action="store_true",
        help="첫 수주 적합도 우선으로 정렬합니다.",
    )
    args = parser.parse_args()

    items = load_items(args.input)
    sort_key = (
        first_win_sort_key
        if args.first_win
        else (lambda row: row["score"])
    )
    scored = sorted(
        (score(item, remote_only=args.remote_only) for item in items),
        key=sort_key,
        reverse=True,
    )
    print(json.dumps(scored, ensure_ascii=False, indent=2 if args.pretty else None))


if __name__ == "__main__":
    main()
