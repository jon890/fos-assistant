package com.bifos.assistant.chat.nextturn.application;

import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.chat.nextturn.application.dto.DelegatedRunActivity;
import com.bifos.assistant.chat.turn.application.ConversationEventHub;
import com.bifos.assistant.chat.turn.application.DelegationLines;
import com.bifos.assistant.chat.turn.application.dto.ChatEvent;
import com.bifos.assistant.runtime.application.dto.RunEvent;
import com.bifos.assistant.runtime.application.dto.RunEventKind;
import com.bifos.assistant.usage.execution.application.port.ConnectorToolNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 대화의 turn 이 바로 맡긴 실행의 도구 사건을 그 대화를 보는 모든 창에 {@code delegation_activity} 로 보낸다(스펙 assistant-hub REQ3).
 *
 * <p>{@link DelegationLineNotifier} 와 같이 대화 단위 SSE 로만 보낸다. 손자 위임의 사건은 보내지 않는다. 보는 사람마다의 가리기는
 * 구독 쪽이 {@link ChatEvent#forViewer} 로 한다. 보내다 실패해도 실행과 기록에는 영향이 없다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DelegationActivityNotifier {
    private final DelegationLines lines;
    private final ConversationRepository conversations;
    private final ConversationEventHub hub;
    private final ConnectorToolNames connectorToolNames;


    @EventListener
    public void onActivity(DelegatedRunActivity activity) {
        if (activity.conversationId() == null || activity.executionId() == null) {
            return;
        }
        RunEvent event = activity.event();
        String phase = event.kind() == RunEventKind.TOOL_STARTED ? ChatEvent.STARTED : ChatEvent.COMPLETED;
        boolean started = ChatEvent.STARTED.equals(phase);
        try {
            lines.directDelegation(activity.executionId())
                .ifPresent(line -> conversations.findById(activity.conversationId())
                    .map(Conversation::publicId)
                    .ifPresent(publicId -> hub.publish(
                        activity.conversationId(),
                        DelegationActivityEvents.of(
                            publicId,
                            line,
                            event.toolName(),
                            event.detail(),
                            phase,
                            started ? null : event.durationMs(),
                            started ? null : event.failed(),
                            connectorToolNames.nameOf(event.toolName())))));
        } catch (RuntimeException ex) {
            log.warn(
                "맡긴 실행의 도구 사건을 화면에 알리지 못했다 executionId={} kind={}",
                activity.executionId(),
                ex.getClass().getSimpleName());
        }
    }
}
