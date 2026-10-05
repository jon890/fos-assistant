package com.bifos.assistant.proactive.application;

import com.bifos.assistant.proactive.application.model.UnreadCheckReport;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 지금 화면이 저장소에 직접 접근하지 않고 요청자의 미열람 보고를 읽는 진입점이다. */
@Service
@RequiredArgsConstructor
public class ProactiveReportSource {

    private final ProactiveCheckRepository checks;

    @Transactional(readOnly = true)
    public List<UnreadCheckReport> unreadOf(Long userId) {
        return checks.findByUserIdAndReportIsNotNullAndReportOpenedAtIsNullOrderByStartedAtDesc(userId).stream()
                .map(check -> new UnreadCheckReport(
                        check.id(),
                        check.agentId(),
                        check.conversationId(),
                        check.startedAt(),
                        check.finishedAt(),
                        check.report()))
                .toList();
    }
}
