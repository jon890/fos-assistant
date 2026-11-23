package com.bifos.assistant.chat.nextturn.application;

import com.bifos.assistant.chat.turn.application.dto.ChatEvent;
import com.bifos.assistant.chat.turn.application.dto.DelegationLine;
import com.bifos.assistant.usage.execution.application.dto.ConnectorToolName;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * {@code delegation_activity} 사건을 만든다. 대화의 turn 이 바로 맡긴 실행의 도구 사건 하나다(스펙 assistant-hub REQ3).
 *
 * <p>칸은 {@code tool} 사건과 같고, 맡은 실행 번호를 {@code executionId} 에, 맡은 에이전트의 이름표를 {@code delegation} 에 넣는다.
 * 보는 사람마다의 가리기는 {@link ChatEvent#forViewer} 가 {@code tool} 과 같게 한다. {@code ChatEvent} 가 파일 길이 상한에 가까워 이
 * 사건을 만드는 일은 여기에 둔다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class DelegationActivityEvents {
    static final String TYPE = "delegation_activity";


    static ChatEvent of(
        UUID conversationId,
        DelegationLine delegation,
        String toolName,
        String detail,
        String phase,
        Long durationMs,
        Boolean failed,
        ConnectorToolName connector) {
        return new ChatEvent(TYPE, null, toolName, detail, conversationId, null, delegation.executionId(), null, null,
            phase, durationMs, failed, null, null, null, null, null, null, null, null, delegation, null, null, null,
            connector);
    }
}
