#!/usr/bin/env python3
"""감사 가능한 외주 공고 원자료를 공통 표 형식의 HTML 리포트로 렌더링한다."""

from __future__ import annotations

import argparse
import html
import json
from datetime import date as date_type
from pathlib import Path

from score_opportunities import score


DEFAULT_PLATFORM_ORDER = ("위시켓", "프리모아", "원티드 긱스")
ACTION_LABELS = {
    "apply-now": "바로 지원",
    "clarify-first": "조건 확인 후 지원",
    "avoid": "비추천",
}


def escape(value: object) -> str:
    return html.escape(str(value), quote=True)


def load_candidates(path: Path) -> list[dict]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(payload, list):
        return payload
    if isinstance(payload, dict) and isinstance(payload.get("items"), list):
        return [
            item
            for item in payload["items"]
            if item.get("eligibility_status", "candidate") == "candidate"
        ]
    raise ValueError("입력 JSON은 공고 배열이거나 items 배열을 가진 객체여야 합니다.")


def platform_order(rows: list[dict]) -> list[str]:
    present = list(dict.fromkeys(str(row["platform"]) for row in rows))
    preferred = [platform for platform in DEFAULT_PLATFORM_ORDER if platform in present]
    return preferred + [platform for platform in present if platform not in preferred]


def selected_action(row: dict) -> str:
    if row.get("recommended_track") == "first-win":
        return str(row.get("first_win_action", row["action"]))
    return str(row["action"])


def selected_score(row: dict) -> float:
    if row.get("recommended_track") == "first-win":
        return float(row.get("first_win_score", row["score"]))
    return float(row["score"])


def score_label(row: dict) -> str:
    track = "첫 수주" if row.get("recommended_track") == "first-win" else "일반"
    return f"{track} {int(selected_score(row))}점"


def rank_key(row: dict) -> tuple[bool, float, float]:
    return (
        row.get("recommended_track") == "first-win"
        and selected_action(row) != "avoid",
        selected_score(row),
        float(row["score"]),
    )


def applicants_label(row: dict) -> str:
    if row.get("applicants") is not None:
        return f"{int(row['applicants'])}명"
    return str(row.get("applicants_display") or "미확인")


def render_table(rows: list[dict], label: str) -> str:
    body = []
    for row in rows:
        action = selected_action(row)
        cells = (
            f'<a href="{escape(row["url"])}" target="_blank" rel="noopener noreferrer">{escape(row["title"])}</a>',
            escape(row.get("budget_display") or "미확인"),
            escape(row.get("duration_display") or "미확인"),
            escape(applicants_label(row)),
            escape(score_label(row)),
            f'<span class="badge {escape(action)}">{escape(ACTION_LABELS[action])}</span>',
            escape(row.get("fit_comment") or "적합성 근거를 확인해야 한다."),
            escape(row.get("check_first") or "범위와 검수 기준을 먼저 확인한다."),
        )
        body.append("<tr>" + "".join(f"<td>{cell}</td>" for cell in cells) + "</tr>")
    return f'''<div class="table-wrap"><table aria-label="{escape(label)}">
      <thead><tr><th>공고</th><th>예산</th><th>기간</th><th>지원자</th><th>적합도</th><th>판단</th><th>한줄평</th><th>먼저 확인할 것</th></tr></thead>
      <tbody>{''.join(body)}</tbody>
    </table></div>'''


def render_document(items: list[dict], report_date: str) -> str:
    scored = [score(item, remote_only=True) for item in items]
    has_remote_uncertainty = any(
        row.get("remote_only_pass") is None for row in scored
    )
    platforms = platform_order(scored)
    by_platform = {
        platform: sorted(
            (row for row in scored if row["platform"] == platform),
            key=rank_key,
            reverse=True,
        )
        for platform in platforms
    }
    review = [row for row in scored if selected_action(row) != "avoid"]
    apply_now = [row for row in review if selected_action(row) == "apply-now"]
    clarify = [row for row in review if selected_action(row) == "clarify-first"]
    shortlist = {
        platform: [row for row in rows if selected_action(row) != "avoid"][:3]
        for platform, rows in by_platform.items()
    }
    actions = sorted(
        review,
        key=lambda row: (selected_action(row) == "apply-now", rank_key(row)),
        reverse=True,
    )[:3]

    top_sections = "".join(
        f'<section class="platform"><h3>{escape(platform)}</h3>{render_table(rows, platform + " 상위 후보") if rows else "<p>현재 추천 기준을 통과한 후보가 없습니다.</p>"}</section>'
        for platform, rows in shortlist.items()
    )
    full_sections = "".join(
        f'<section class="platform"><h3>{escape(platform)}</h3>{render_table(rows, platform + " 수집한 공고") if rows else "<p>현재 조건에 맞는 공고가 없습니다.</p>"}</section>'
        for platform, rows in by_platform.items()
    )
    action_items = "".join(
        f'<li><a href="{escape(row["url"])}" target="_blank" rel="noopener noreferrer">{escape(row["title"])}</a><span>{escape(row.get("check_first") or "범위를 확인한다.")}</span></li>'
        for row in actions
    ) or "<li>현재 바로 검토할 후보가 없습니다.</li>"
    access_note = (
        '<p class="notice">일부 공고는 공개 상세만으로 원격 여부를 확정할 수 없어, '
        "지원 전에 근무·미팅 방식을 확인해야 합니다.</p>"
        if has_remote_uncertainty
        else ""
    )

    return f'''<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>외주 기회 리포트 · {escape(report_date)}</title>
  <style>
    :root {{ color-scheme:light; --ink:#14213d; --muted:#5f6b7a; --line:#dbe3ee; --paper:#fff; --wash:#f4f7fb; --accent:#2356d8; --good:#16794a; --warn:#9a5b00; --bad:#9b2c2c; }}
    * {{ box-sizing:border-box; }}
    body {{ margin:0; background:var(--wash); color:var(--ink); font-family:Pretendard,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif; line-height:1.55; }}
    main {{ width:min(1480px,calc(100% - 32px)); margin:32px auto 64px; }}
    header, section {{ background:var(--paper); border:1px solid var(--line); border-radius:18px; padding:24px; margin:16px 0; box-shadow:0 8px 24px rgba(20,33,61,.05); }}
    section.platform {{ padding:0; border:0; box-shadow:none; }}
    h1 {{ margin:0 0 8px; font-size:clamp(1.7rem,4vw,2.6rem); letter-spacing:-.04em; }}
    h2 {{ margin:0 0 16px; font-size:1.35rem; }}
    h3 {{ margin:24px 0 10px; font-size:1.08rem; }}
    h3:first-child {{ margin-top:0; }}
    p {{ margin:8px 0; }}
    .sub {{ color:var(--muted); }}
    .notice {{ margin-top:16px; padding:12px 14px; border-left:4px solid var(--warn); background:#fff8e8; color:#694100; }}
    .metrics {{ display:grid; grid-template-columns:repeat(3,1fr); gap:12px; margin-top:22px; }}
    .metric {{ background:var(--wash); border-radius:14px; padding:16px; }}
    .metric strong {{ display:block; font-size:1.8rem; color:var(--accent); }}
    .table-wrap {{ overflow-x:auto; border:1px solid var(--line); border-radius:12px; }}
    table {{ width:100%; min-width:1320px; border-collapse:collapse; font-size:.9rem; }}
    th, td {{ padding:12px; text-align:left; vertical-align:top; border-bottom:1px solid var(--line); }}
    th {{ background:#eaf0fb; white-space:nowrap; }}
    tr:last-child td {{ border-bottom:0; }}
    td:nth-child(1) {{ min-width:230px; }}
    td:nth-child(2), td:nth-child(3), td:nth-child(4), td:nth-child(5), td:nth-child(6) {{ white-space:nowrap; }}
    td:nth-child(7), td:nth-child(8) {{ min-width:250px; }}
    a {{ color:var(--accent); font-weight:700; text-decoration:none; }}
    a:hover {{ text-decoration:underline; }}
    .badge {{ display:inline-block; border-radius:999px; padding:3px 9px; font-weight:700; }}
    .apply-now {{ background:#e3f6ec; color:var(--good); }}
    .clarify-first {{ background:#fff2d9; color:var(--warn); }}
    .avoid {{ background:#fde8e8; color:var(--bad); }}
    .actions {{ margin:0; padding-left:22px; }}
    .actions li {{ margin:12px 0; }}
    .actions span {{ display:block; color:var(--muted); }}
    @media (max-width:720px) {{ main {{ width:min(100% - 20px,1480px); margin-top:12px; }} header,section {{ padding:17px; border-radius:14px; }} .metrics {{ grid-template-columns:1fr; }} }}
  </style>
</head>
<body>
<main>
  <header>
    <p class="sub">{escape(report_date)} 기준 · 지원자 수는 수집 시점의 현재 값</p>
    <h1>외주 기회 리포트</h1>
    <p>원격 조건을 통과한 코딩 외주 가운데, 백엔드와 AI 연동 경험을 활용하기 좋은 공고를 우선했습니다.</p>
    {access_note}
    <div class="metrics">
      <div class="metric"><span>검토 후보</span><strong>{len(review)}</strong></div>
      <div class="metric"><span>바로 지원</span><strong>{len(apply_now)}</strong></div>
      <div class="metric"><span>조건 확인 후 지원</span><strong>{len(clarify)}</strong></div>
    </div>
  </header>
  <section><h2>결론</h2><p>백엔드·AI 연동 범위가 분명하고 경쟁 강도가 낮은 공고부터 확인하는 편이 좋습니다.</p></section>
  <section><h2>플랫폼별 상위 후보</h2>{top_sections}</section>
  <section><h2>이번 회차 액션</h2><ol class="actions">{action_items}</ol></section>
  <section><h2>의논할 결정</h2><p>지원자가 많은 고적합 공고와 범위 확인이 필요한 저경쟁 공고 중 어느 쪽을 먼저 공략할지 정하면 지원문 우선순위를 정할 수 있습니다.</p></section>
  <section><h2>수집한 공고 목록</h2>{full_sections}</section>
</main>
</body>
</html>
'''


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("--date", default=date_type.today().isoformat())
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    candidates = load_candidates(args.input)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(render_document(candidates, args.date), encoding="utf-8")
    print(json.dumps({"output": str(args.output), "candidate_count": len(candidates)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
