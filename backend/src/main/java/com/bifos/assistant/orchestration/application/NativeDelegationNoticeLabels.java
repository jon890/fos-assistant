package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.chat.delivery.application.port.AutoTurnNoticeLabels;
import com.bifos.assistant.orchestration.domain.HermesSessionBinding;
import com.bifos.assistant.orchestration.infra.HermesSessionBindingRepository;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 내장 위임 결과를 처음 전한 알림 줄마다 그 결과의 상태를 준다(ADR-20261117 / native-delegation-wake, 설계 DECISION40). */
@Component
@RequiredArgsConstructor
public class NativeDelegationNoticeLabels implements AutoTurnNoticeLabels {

    private final HermesSessionBindingRepository bindings;


    @Override
    public Map<Long, String> statusesByNotice(Long conversationId) {
        Map<Long, String> statuses = new HashMap<>();
        for (HermesSessionBinding row : bindings.findNoticedInConversation(conversationId)) {
            statuses.put(row.noticeMessageId(), row.resultStatus().name());
        }
        return statuses;
    }
}
