package com.bifos.assistant.chat.turn.application.dto;

import com.bifos.assistant.chat.delivery.application.port.AutoTurnResultSource;
import java.util.List;
import java.util.Map;

/**
 * 사용자가 보낸 turn 의 입력 앞에 넣는 결과 단락과 그 결과의 열쇠다(ADR-20261117 / native-delegation-wake, 스펙 agent-team DECISION34).
 *
 * @param text 결과 단락. 빈 글이면 넣을 것이 없다
 * @param keys 결과를 낸 쪽마다 넣은 결과의 열쇠들
 */
public record CarriedResults(String text, Map<AutoTurnResultSource, List<String>> keys) {
    /** 사용자 메시지 앞에 붙이는 머리다. {@code MentionInputs} 의 머리와 같은 글이다. */
    private static final String USER_MESSAGE_LABEL = "[사용자 메시지]\n";


    public CarriedResults {
        keys = Map.copyOf(keys);
    }


    public static CarriedResults none() {
        return new CarriedResults("", Map.of());
    }


    /** 대화방 요약과 맡김 단락 앞에 결과 단락을 둔다. 뒤에 붙일 단락이 없으면 사용자 메시지 머리를 붙인다. */
    public String prependTo(String lead) {
        if (text.isEmpty()) {
            return lead;
        }
        return text + (lead.isEmpty() ? USER_MESSAGE_LABEL : lead);
    }
}
