package com.bifos.assistant.proactive.application;

import com.bifos.assistant.chat.application.CheckReportReads;
import com.bifos.assistant.proactive.infra.ProactiveCheckRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 점검 대화를 읽은 사용자의 그 대화 보고를 연 것으로 적는다. 다른 사용자와 다른 대화의 보고는 건드리지 않는다. */
@Component
@RequiredArgsConstructor
public class CheckReportReadMarker implements CheckReportReads {

    private final ProactiveCheckRepository checks;

    @Override
    @Transactional
    public void markRead(Long userId, Long conversationId, Instant now) {
        checks.findByUserIdAndConversationIdAndReportIsNotNullAndReportOpenedAtIsNull(userId, conversationId)
                .forEach(check -> check.openReport(now));
    }
}
