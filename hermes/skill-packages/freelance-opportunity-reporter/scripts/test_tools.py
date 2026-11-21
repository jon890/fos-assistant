#!/usr/bin/env python3

from __future__ import annotations

import json
import os
import subprocess
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from assemble_report_data import assemble
from audit_collection import audit
from finalize_report import artifact_paths, ensure_combined
from render_report import load_candidates, rank_key, render_document
from score_opportunities import first_win_sort_key, score


class ScoreOpportunitiesTest(unittest.TestCase):
    def test_small_clear_project_is_promoted_to_first_win_track(self) -> None:
        item = {
            "platform": "Wishket",
            "title": "거래소 공지 감지 스크립트",
            "budget_krw": 200_000,
            "duration_days": 1,
            "applicants": 4,
            "fit": 4,
            "risk": 3,
            "portfolio": 3,
            "remote": 3,
            "remote_only_pass": None,
            "scope_clarity": 5,
            "delivery_confidence": 4,
            "experience_match": 4,
            "reputation_value": 5,
        }

        result = score(item, remote_only=True)

        self.assertEqual(result["score"], 62)
        self.assertEqual(result["action"], "clarify-first")
        self.assertEqual(result["first_win_score"], 77)
        self.assertEqual(result["first_win_action"], "clarify-first")
        self.assertEqual(result["recommended_track"], "first-win")

    def test_remote_block_applies_to_first_win_track(self) -> None:
        item = {
            "budget_krw": 500_000,
            "duration_days": 3,
            "applicants": 1,
            "fit": 5,
            "risk": 1,
            "remote": 1,
            "remote_only_pass": False,
            "scope_clarity": 5,
            "delivery_confidence": 5,
            "experience_match": 5,
            "reputation_value": 5,
        }

        result = score(item, remote_only=True)

        self.assertEqual(result["first_win_score"], 49)
        self.assertEqual(result["first_win_action"], "avoid")
        self.assertEqual(result["recommended_track"], "standard")

    def test_long_project_stays_on_standard_track(self) -> None:
        result = score(
            {
                "budget_krw": 10_000_000,
                "duration_days": 60,
                "fit": 5,
                "risk": 2,
                "portfolio": 5,
                "remote": 5,
                "remote_only_pass": True,
            },
            remote_only=True,
        )

        self.assertNotIn("first_win_score", result)
        self.assertEqual(result["recommended_track"], "standard")

    def test_unknown_budget_is_clarified_not_dropped(self) -> None:
        result = score(
            {
                "budget_krw": None,
                "duration_days": 3,
                "applicants": 2,
                "fit": 5,
                "risk": 2,
                "remote": 5,
                "remote_only_pass": True,
                "scope_clarity": 5,
                "delivery_confidence": 5,
                "experience_match": 5,
                "reputation_value": 5,
            },
            remote_only=True,
        )

        self.assertEqual(result["recommended_track"], "first-win")
        self.assertEqual(result["first_win_action"], "clarify-first")

    def test_zero_budget_is_not_first_win_even_when_explicitly_enabled(self) -> None:
        result = score(
            {
                "first_win_candidate": True,
                "budget_krw": 0,
                "duration_days": 3,
                "fit": 5,
                "risk": 1,
                "remote": 5,
                "remote_only_pass": True,
            },
            remote_only=True,
        )

        self.assertEqual(result["recommended_track"], "standard")
        self.assertNotIn("first_win_score", result)

    def test_first_win_sort_does_not_promote_avoid_candidate(self) -> None:
        standard = {
            "score": 100,
            "recommended_track": "standard",
        }
        rejected_first_win = {
            "score": 49,
            "first_win_score": 49,
            "first_win_action": "avoid",
            "recommended_track": "standard",
        }

        rows = sorted(
            [rejected_first_win, standard],
            key=first_win_sort_key,
            reverse=True,
        )

        self.assertIs(rows[0], standard)


class AuditCollectionTest(unittest.TestCase):
    def test_reports_complete_collection(self) -> None:
        payload = {
            "collection": [
                {
                    "platform": "Wishket",
                    "source_id": "wishket-outsourcing-open",
                    "advertised_count": 1,
                }
            ],
            "items": [
                {
                    "platform": "Wishket",
                    "project_id": "157210",
                    "title": "공지 감지",
                    "url": "https://www.wishket.com/project/157210/",
                    "registered_at": "2026-07-27",
                    "collected_at": "2026-07-27T16:15:00+09:00",
                    "list_page": 1,
                    "collection_source": "wishket-outsourcing-open",
                    "detail_status": "confirmed",
                    "eligibility_status": "candidate",
                    "exclusion_reason": None,
                }
            ],
        }

        result = audit(payload)

        self.assertEqual(result["status"], "complete")
        self.assertEqual(result["coverage"][0]["missing_count"], 0)

    def test_reports_missing_items_and_duplicate_ids(self) -> None:
        item = {
            "platform": "Wishket",
            "project_id": "157210",
            "title": "공지 감지",
            "url": "https://www.wishket.com/project/157210/",
            "registered_at": "2026-07-27",
            "collected_at": "2026-07-27T16:15:00+09:00",
            "list_page": 1,
            "collection_source": "wishket-outsourcing-open",
            "detail_status": "confirmed",
            "eligibility_status": "candidate",
            "exclusion_reason": None,
        }
        payload = {
            "collection": [
                {
                    "platform": "Wishket",
                    "source_id": "wishket-outsourcing-open",
                    "advertised_count": 3,
                }
            ],
            "items": [item, item],
        }

        result = audit(payload)

        self.assertEqual(result["status"], "incomplete")
        self.assertEqual(result["unique_item_count"], 1)
        self.assertEqual(result["coverage"][0]["missing_count"], 2)
        self.assertEqual(result["duplicate_keys"][0]["count"], 2)

    def test_requires_collection_metadata_for_coverage_claim(self) -> None:
        payload = [
            {
                "platform": "Wishket",
                "project_id": "157210",
                "title": "공지 감지",
                "url": "https://www.wishket.com/project/157210/",
                "registered_at": "2026-07-27",
                "collected_at": "2026-07-27T16:15:00+09:00",
                "list_page": 1,
                "collection_source": "wishket-outsourcing-open",
                "detail_status": "confirmed",
                "eligibility_status": "candidate",
                "exclusion_reason": None,
            }
        ]

        result = audit(payload)

        self.assertEqual(result["status"], "incomplete")
        self.assertEqual(result["untracked_sources"], ["wishket-outsourcing-open"])


class RenderReportTest(unittest.TestCase):
    def setUp(self) -> None:
        self.items = [
            {
                "platform": "위시켓",
                "project_id": str(index),
                "title": f"백엔드 공고 {index}",
                "url": f"https://example.com/{index}",
                "budget_display": "1,000만 원",
                "budget_krw": 10_000_000,
                "duration_display": "30일",
                "duration_days": 30,
                "applicants": index,
                "fit": 5,
                "risk": 2,
                "portfolio": 5,
                "remote": 5,
                "remote_only_pass": True,
                "scope_clarity": 5,
                "delivery_confidence": 5,
                "experience_match": 5,
                "reputation_value": 5,
                "fit_comment": "백엔드 경험과 잘 맞는다.",
                "check_first": "검수 범위를 확인한다.",
                "eligibility_status": "candidate",
            }
            for index in range(1, 5)
        ]

    def test_uses_same_eight_columns_and_limits_shortlist(self) -> None:
        document = render_document(self.items, "2026-08-11")

        header = "<th>공고</th><th>예산</th><th>기간</th><th>지원자</th><th>적합도</th><th>판단</th><th>한줄평</th><th>먼저 확인할 것</th>"
        self.assertEqual(document.count(header), 2)
        self.assertEqual(document.count('aria-label="위시켓 상위 후보"'), 1)
        top_table = document.split('aria-label="위시켓 상위 후보"', 1)[1].split("</table>", 1)[0]
        self.assertEqual(top_table.count("<tr>"), 4)
        self.assertIn("4명", document)

    def test_excludes_non_candidate_rows_from_report(self) -> None:
        payload = {"items": [self.items[0], {**self.items[1], "eligibility_status": "excluded"}]}
        with TemporaryDirectory() as directory:
            path = Path(directory) / "items.json"
            path.write_text(json.dumps(payload), encoding="utf-8")

            candidates = load_candidates(path)

        self.assertEqual([row["project_id"] for row in candidates], ["1"])

    def test_discloses_unknown_remote_status(self) -> None:
        document = render_document(
            [{**self.items[0], "remote_only_pass": None}],
            "2026-08-11",
        )

        self.assertIn("공개 상세만으로 원격 여부를 확정할 수 없어", document)

    def test_ranks_viable_first_win_before_standard_candidate(self) -> None:
        first_win = score(
            {
                **self.items[0],
                "duration_days": 8,
                "duration_display": "8일",
                "risk": 3,
            },
            remote_only=True,
        )
        standard = score(self.items[1], remote_only=True)

        ranked = sorted([standard, first_win], key=rank_key, reverse=True)

        self.assertEqual(ranked[0]["recommended_track"], "first-win")

    def test_action_list_puts_apply_now_before_clarify_first(self) -> None:
        apply_now = {**self.items[0], "project_id": "apply", "title": "바로 지원 공고"}
        clarify = {
            **self.items[1],
            "project_id": "clarify",
            "title": "먼저 확인 공고",
            "fit": 3,
            "risk": 4,
            "portfolio": 2,
        }

        document = render_document([clarify, apply_now], "2026-08-11")
        action_section = document.split("<h2>이번 회차 액션</h2>", 1)[1].split("</section>", 1)[0]

        self.assertLess(action_section.index("바로 지원 공고"), action_section.index("먼저 확인 공고"))


class AssembleReportDataTest(unittest.TestCase):
    def test_adds_audit_fields_and_excludes_non_remote_rows(self) -> None:
        rows = [
            {
                "platform": "원티드 긱스",
                "project_id": "1",
                "title": "원격 백엔드",
                "url": "https://example.com/1",
                "registered_at": None,
                "list_page": 1,
                "detail_status": "confirmed",
                "remote_only_pass": True,
                "fit": 5,
            },
            {
                "platform": "원티드 긱스",
                "project_id": "2",
                "title": "상주 백엔드",
                "url": "https://example.com/2",
                "registered_at": None,
                "list_page": 1,
                "detail_status": "confirmed",
                "remote_only_pass": False,
                "fit": 5,
            },
        ]
        with TemporaryDirectory() as directory:
            path = Path(directory) / "wanted.json"
            path.write_text(json.dumps(rows), encoding="utf-8")

            payload = assemble([path], "2026-08-11T10:30:00+09:00")

        self.assertEqual(payload["collection"][0]["advertised_count"], 2)
        self.assertEqual(payload["items"][0]["eligibility_status"], "candidate")
        self.assertEqual(payload["items"][1]["eligibility_status"], "excluded")
        self.assertEqual(audit(payload)["status"], "complete")

    def test_excludes_rows_with_unconfirmed_remote_status(self) -> None:
        rows = [
            {
                "platform": "위시켓",
                "project_id": "157442",
                "title": "프라이빗 백엔드 공고",
                "url": "https://www.wishket.com/project/157442/",
                "registered_at": None,
                "list_page": 1,
                "detail_status": "inaccessible",
                "remote_only_pass": None,
                "fit": 5,
            }
        ]
        with TemporaryDirectory() as directory:
            path = Path(directory) / "wishket.json"
            path.write_text(json.dumps(rows), encoding="utf-8")

            payload = assemble([path], "2026-08-11T10:30:00+09:00")

        self.assertEqual(payload["items"][0]["eligibility_status"], "excluded")
        self.assertEqual(payload["items"][0]["exclusion_reason"], "완전 원격 조건을 확인하지 못함")

    def test_related_items_do_not_hide_primary_list_gap(self) -> None:
        def item(project_id: str, source: str) -> dict:
            return {
                "platform": "Wishket",
                "project_id": project_id,
                "title": f"공고 {project_id}",
                "url": f"https://www.wishket.com/project/{project_id}/",
                "registered_at": "2026-07-27",
                "collected_at": "2026-07-27T16:15:00+09:00",
                "list_page": 1,
                "collection_source": source,
                "detail_status": "confirmed",
                "eligibility_status": "candidate",
                "exclusion_reason": None,
            }

        payload = {
            "collection": [
                {
                    "platform": "Wishket",
                    "source_id": "wishket-outsourcing-open",
                    "advertised_count": 2,
                },
                {
                    "platform": "Wishket",
                    "source_id": "wishket-related",
                    "advertised_count": None,
                    "coverage_expected": False,
                },
            ],
            "items": [
                item("1", "wishket-outsourcing-open"),
                item("2", "wishket-related"),
            ],
        }

        result = audit(payload)

        self.assertEqual(result["status"], "incomplete")
        self.assertEqual(result["coverage"][0]["missing_count"], 1)
        self.assertEqual(result["coverage"][1]["coverage_status"], "not-applicable")


class FinalizeReportTest(unittest.TestCase):
    def test_combined_data_is_rebuilt_when_platform_collection_is_newer(self) -> None:
        report_date = "2026-08-24"
        with TemporaryDirectory() as directory:
            runtime_dir = Path(directory)
            source = runtime_dir / f"wishket-enriched-{report_date}.json"
            source.write_text(json.dumps([{"platform": "위시켓", "title": "새 공고"}]), encoding="utf-8")
            paths = artifact_paths(runtime_dir, report_date)
            combined = paths["combined"]
            self.assertIsInstance(combined, Path)
            previous = {"items": [{"title": "이전 공고"}]}
            combined.write_text(json.dumps(previous), encoding="utf-8")

            os.utime(source, (1, 1))
            os.utime(combined, (2, 2))
            self.assertEqual(ensure_combined(paths, report_date), combined)
            self.assertEqual(json.loads(combined.read_text(encoding="utf-8")), previous)

            os.utime(source, (3, 3))
            self.assertEqual(ensure_combined(paths, report_date), combined)
            self.assertEqual(json.loads(combined.read_text(encoding="utf-8"))["items"][0]["title"], "새 공고")

    def test_cli_builds_summary_and_keeps_local_html(self) -> None:
        item = {
            "platform": "위시켓",
            "project_id": "1",
            "title": "백엔드 자동화",
            "url": "https://example.com/1",
            "registered_at": "2026-08-24",
            "collected_at": "2026-08-24T07:30:00+09:00",
            "list_page": 1,
            "collection_source": "wishket-active-scope",
            "detail_status": "confirmed",
            "eligibility_status": "candidate",
            "exclusion_reason": None,
            "budget_krw": 10_000_000,
            "duration_days": 30,
            "applicants": 3,
            "fit": 5,
            "risk": 2,
            "portfolio": 5,
            "remote": 5,
            "remote_only_pass": True,
            "scope_clarity": 5,
            "delivery_confidence": 5,
            "experience_match": 5,
            "reputation_value": 5,
        }
        payload = {
            "collection": [
                {
                    "platform": "위시켓",
                    "source_id": "wishket-active-scope",
                    "advertised_count": 1,
                }
            ],
            "items": [item],
        }
        with TemporaryDirectory() as directory:
            runtime_dir = Path(directory)
            paths = artifact_paths(runtime_dir, "2026-08-24")
            combined = paths["combined"]
            self.assertIsInstance(combined, Path)
            combined.write_text(json.dumps(payload), encoding="utf-8")
            result = subprocess.run(
                [
                    sys.executable, str(Path(__file__).with_name("finalize_report.py")),
                    "--date", "2026-08-24", "--runtime-dir", directory,
                ],
                check=True,
                capture_output=True,
                text=True,
            )
            summary = json.loads(result.stdout)
            html_path = Path(summary["html"])

            self.assertEqual(summary["status"], "ready")
            self.assertEqual(summary["candidate_count"], 1)
            self.assertEqual(summary["audit_status"], "complete")
            self.assertEqual(html_path, paths["html"])
            self.assertIn("수집한 공고 목록", html_path.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
