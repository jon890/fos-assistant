package com.bifos.assistant.connector.handoff.application;

import com.bifos.assistant.browser.application.BrowserLeftTabs;
import com.bifos.assistant.chat.turn.application.ConversationNotices;
import com.bifos.assistant.connector.action.domain.ConnectorAction;
import com.bifos.assistant.connector.action.domain.type.HandoffNeed;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.connection.infra.ConnectorConnectionRepository;
import com.bifos.assistant.connector.handoff.application.dto.HandoffCloseOutcome;
import com.bifos.assistant.connector.handoff.application.dto.HandoffCloseResult;
import com.bifos.assistant.connector.handoff.infra.ConnectorHandoffRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 넘기기 카드의 단추가 그 카드 하나를 닫는다(spec REQ3, REQ4). 닫기는 열린 줄에만 시각을 적는 조건부 갱신이라, 같은 카드를 두 창에서 눌러도
 * 처음 닫은 요청만 단추의 글을 보내라는 답을 받는다(spec DECISION15). 글은 웹이 다음 메시지로 보낸다(설계 DECISION2).
 *
 * <p>「다 했어요」 는 닫기 전에 이어 갈 수 있는지 본다. 남긴 탭이 없어졌거나 연결이 끊겼으면 카드를 닫고 대화에 알림 줄을 남긴 뒤 이어 가지 않는다.
 * 탭 확인은 CDP 를 부르므로 트랜잭션 밖에서 한다. 표는 {@code docs/features/mobile-default-chat/design.md} 의 「단추 닫기 API」 가 갖는다.
 */
@Service
@RequiredArgsConstructor
public class HandoffButtons {

    static final String TAB_GONE = "화면이 닫혀 이어 갈 수 없어요. 다시 맡겨 주세요.";
    static final String CONNECTION_GONE = "연결이 끊겨 이어 갈 수 없어요. 「외부 서비스 연결」을 확인한 뒤 다시 맡겨 주세요.";

    private final ConnectorHandoffRepository actions;
    private final ConnectorHandoffs handoffs;
    private final ConnectorConnectionRepository connections;
    private final BrowserLeftTabs tabs;
    private final ConversationNotices conversations;


    /**
     * 주인의 대화에 있는 넘기기 하나를 닫는다. 대화가 주인의 것인지는 부르는 쪽이 이미 확인했다.
     *
     * @throws ApiException 그 대화의 넘기기가 아니면 {@code CONVERSATION_NOT_FOUND}. 남의 대화 경로와 같은 응답이다
     */
    public HandoffCloseResult close(CurrentUser user, Long conversationId, UUID handoffId, HandoffCloseOutcome outcome) {
        ConnectorAction action = actions.findByPublicId(handoffId)
            .filter(found -> Objects.equals(found.userId(), user.id()) && Objects.equals(found.conversationId(), conversationId))
            .filter(found -> found.handoff() != null && found.handoff().need() != null)
            .orElseThrow(HandoffButtons::notFound);
        if (action.handoff().closedAt() != null) {
            return HandoffCloseResult.ALREADY_CLOSED;
        }
        Optional<String> blocked = outcome == HandoffCloseOutcome.DONE ? blocked(action) : Optional.empty();
        if (!handoffs.close(action, false)) {
            return HandoffCloseResult.ALREADY_CLOSED;
        }
        if (blocked.isPresent()) {
            conversations.post(conversationId, blocked.get());
            return HandoffCloseResult.UNAVAILABLE;
        }
        return outcome == HandoffCloseOutcome.DONE ? HandoffCloseResult.CONTINUE : HandoffCloseResult.STOPPED;
    }


    /** 이어 갈 수 없는 까닭의 알림 줄이다. 이어 갈 수 있으면 비어 있다. */
    private Optional<String> blocked(ConnectorAction action) {
        boolean connected = connections.findByUserIdAndConnectorId(action.userId(), action.connectorId())
            .filter(connection -> connection.status() != ConnectionStatus.DISCONNECTED)
            .isPresent();
        if (!connected) {
            return Optional.of(CONNECTION_GONE);
        }
        return keepsTab(action) && tabs.gone(action.userId(), action.handoff().tab(), action.handoff().at())
            ? Optional.of(TAB_GONE) : Optional.empty();
    }


    /** 이어 가려면 남긴 탭이 있어야 하는 넘기기인가. 결제는 사람이 결제한 뒤 탭을 닫아도 주문 확인을 이어 간다. */
    private static boolean keepsTab(ConnectorAction action) {
        HandoffNeed need = action.handoff().need();
        return action.handoff().tab() != null && need != HandoffNeed.PAYMENT && need != HandoffNeed.TAB_CHECK;
    }


    private static ApiException notFound() {
        return new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "this conversation does not exist");
    }
}
