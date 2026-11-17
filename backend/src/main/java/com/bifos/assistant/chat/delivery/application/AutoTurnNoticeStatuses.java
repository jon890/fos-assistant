package com.bifos.assistant.chat.delivery.application;

import com.bifos.assistant.chat.delivery.application.port.AutoTurnNoticeLabels;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 알림 줄 번호마다 자동 turn 결과의 상태를 모은다. 이력 API 가 내장 위임 결과 줄을 그리는 데 쓴다(ADR-20261117 / native-delegation-wake). */
@Component
@RequiredArgsConstructor
public class AutoTurnNoticeStatuses {

    private final List<AutoTurnNoticeLabels> labels;


    /** 그 대화의 알림 줄 번호마다 상태다. 구현이 없으면 비어 있다. 주인 확인은 부르는 쪽이 마친 번호로 부른다. */
    public Map<Long, String> byNotice(Long conversationId) {
        Map<Long, String> statuses = new HashMap<>();
        labels.forEach(label -> statuses.putAll(label.statusesByNotice(conversationId)));
        return statuses;
    }
}
