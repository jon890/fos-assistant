package com.bifos.assistant.proactive;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.proactive.application.CheckReportApprovalSource;
import com.bifos.assistant.proactive.application.CheckReportFactory;
import com.bifos.assistant.proactive.application.CheckResultParser;
import com.bifos.assistant.proactive.application.FindingJudgement;
import com.bifos.assistant.proactive.application.model.CheckResultBlock;
import com.bifos.assistant.proactive.application.model.JudgedFinding;
import com.bifos.assistant.proactive.domain.CheckReport;
import com.bifos.assistant.proactive.domain.type.CheckOutcome;
import com.bifos.assistant.proactive.domain.type.FindingKind;
import com.bifos.assistant.proactive.domain.type.FindingReason;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 보고의 모델 글과 Control Plane이 채울 값을 섞지 않는지 본다. */
class CheckReportFactoryTest {

    @Test
    @DisplayName("근거는 검사한 sourceUrl 세 개까지, 승인은 실행 트리의 실제 공개 식별자로 채운다")
    void createsReportFromVerifiedSourcesAndPendingApprovals() {
        UUID approval = UUID.fromString("7c9955be-0000-4000-8000-000000000001");
        CheckReportApprovalSource approvals = rootId -> List.of(approval);
        CheckReportFactory factory = new CheckReportFactory(approvals);
        CheckResultBlock block = new CheckResultBlock(
                2,
                CheckOutcome.FINDINGS,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new CheckResultBlock.ReportDraft(List.of("바뀜"), List.of("확인"), List.of("다음")),
                List.of());
        List<JudgedFinding> judged = List.of(
                new JudgedFinding(
                        null, FindingKind.REFERENCE, FindingReason.CLOSED, "https://example.com/closed", null),
                judged("https://example.com/a"),
                judged("https://example.com/a"),
                judged("https://example.com/b"),
                judged("https://example.com/c"),
                judged("https://example.com/d"));

        CheckReport report = factory.create(block, judged, 31L);

        assertThat(report.changed()).containsExactly("바뀜");
        assertThat(report.done()).containsExactly("확인");
        assertThat(report.next()).containsExactly("다음");
        assertThat(report.evidence())
                .containsExactly("https://example.com/a", "https://example.com/b", "https://example.com/c");
        assertThat(report.needsApproval()).containsExactly(approval.toString());
    }

    private static JudgedFinding judged(String sourceUrl) {
        return new JudgedFinding(null, FindingKind.NEW, null, sourceUrl, null);
    }

    @Test
    @DisplayName("버전 1 보고는 요약과 NEW 발견으로 만들고 마감된 발견은 근거와 다음 일에서 뺀다")
    void adaptsVersionOneUsingOnlyAcceptedFindings() {
        CheckResultBlock block = new CheckResultParser().read("""
                <fos-check-result>
                {"version":1,"outcome":"FINDINGS","summary":"원문을 확인했어요",
                 "findings":[
                  {"title":"마감된 소식","sourceUrl":"https://example.com/closed",
                   "checkedAt":"2026-10-05T00:00:00Z","freshness":"CLOSED",
                   "whyItMatters":"비교","facts":["사실"],"next":{"type":"ACTION","text":"지난 일"}},
                  {"title":"새 소식","sourceUrl":"https://example.com/new",
                   "checkedAt":"2026-10-05T00:00:00Z","freshness":"CURRENT",
                   "whyItMatters":"비교","facts":["사실"],"next":{"type":"QUESTION","text":"조건 확인"}}]}
                </fos-check-result>
                """).block();
        Instant now = Instant.parse("2026-10-05T00:00:00Z");
        List<JudgedFinding> judged = block.findings().stream()
                .map(finding -> FindingJudgement.judge(finding, now, now, Set.of()))
                .toList();

        CheckReport report = new CheckReportFactory(rootId -> List.of()).create(block, judged, null);

        assertThat(report.changed()).containsExactly("새 소식");
        assertThat(report.done()).containsExactly("원문을 확인했어요");
        assertThat(report.next()).containsExactly("조건 확인");
        assertThat(report.evidence()).containsExactly("https://example.com/new");
    }
}
