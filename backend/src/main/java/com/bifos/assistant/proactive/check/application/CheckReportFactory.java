package com.bifos.assistant.proactive.check.application;

import com.bifos.assistant.proactive.check.application.port.CheckReportApprovalSource;
import com.bifos.assistant.proactive.check.application.dto.CheckResultBlock;
import com.bifos.assistant.proactive.check.application.dto.JudgedFinding;
import com.bifos.assistant.proactive.check.application.dto.JudgedProblem;
import com.bifos.assistant.proactive.check.domain.CheckReport;
import com.bifos.assistant.proactive.check.domain.ReportedFinding;
import com.bifos.assistant.proactive.check.domain.type.FindingKind;
import com.bifos.assistant.proactive.check.domain.type.ProblemStatus;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 모델이 제안한 v2 보고에 Control Plane이 확인한 근거와 승인 항목을 채운다. 다음 살펴보기의 되풀이 판정 재료(알린 발견과 받아들인 문제 키)도
 * 함께 담는다.
 */
@Component
@RequiredArgsConstructor
public class CheckReportFactory {

    private final CheckReportApprovalSource approvals;


    public CheckReport create(
        CheckResultBlock block, List<JudgedFinding> judged, List<JudgedProblem> problems, Long rootExecutionId) {
        List<JudgedFinding> accepted = judged.stream()
            .filter(finding -> finding.kind() == FindingKind.NEW)
            .toList();
        CheckResultBlock.ReportDraft draft = block.report();
        List<String> changed;
        List<String> done;
        List<String> next;
        if (draft == null) {
            changed = accepted.stream()
                .map(JudgedFinding::finding)
                .filter(Objects::nonNull)
                .map(CheckResultBlock.Finding::title)
                .filter(Objects::nonNull)
                .limit(3)
                .toList();
            done = accepted.isEmpty() || block.summary() == null ? List.of() : List.of(block.summary());
            next = accepted.stream()
                .map(JudgedFinding::finding)
                .filter(Objects::nonNull)
                .map(CheckResultBlock.Finding::next)
                .filter(Objects::nonNull)
                .map(CheckResultBlock.Next::text)
                .filter(Objects::nonNull)
                .limit(2)
                .toList();
        } else {
            changed = draft.changed();
            done = draft.done();
            next = draft.next();
        }
        List<String> evidence = accepted.stream()
            .map(JudgedFinding::sourceUrl)
            .filter(Objects::nonNull)
            .distinct()
            .limit(3)
            .toList();
        List<String> pending = approvals.pendingPublicIds(rootExecutionId).stream()
            .map(UUID::toString)
            .toList();
        List<ReportedFinding> announced = accepted.stream()
            .filter(finding -> finding.finding() != null)
            .filter(finding -> finding.finding().topicKey() != null && !finding.finding().topicKey().isBlank())
            .map(finding -> new ReportedFinding(
                finding.finding().topicKey(),
                finding.sourceUrl(),
                finding.finding().title()))
            .toList();
        List<String> acceptedProblemKeys = problems.stream()
            .filter(problem -> problem.status() == ProblemStatus.ACCEPTED)
            .map(JudgedProblem::problemKey)
            .filter(key -> !key.isEmpty())
            .distinct()
            .toList();
        return new CheckReport(changed, done, evidence, pending, next, announced, acceptedProblemKeys);
    }
}
