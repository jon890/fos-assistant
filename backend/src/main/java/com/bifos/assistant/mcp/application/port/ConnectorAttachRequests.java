package com.bifos.assistant.mcp.application.port;

import com.bifos.assistant.shared.auth.CurrentUser;
import java.util.Map;

/**
 * 에이전트가 요청자의 연결을 쓰게 해 달라고 청하는 길이다(스펙 service-concepts REQ15, REQ16, 비서 중심 대화 스펙 REQ9). 붙이지 않고
 * 「새 전문 에이전트를 만들어 붙이기」 승인 카드만 띄운다. 구현은 승인 줄을 가진 {@code connector} 가 갖고 mcp 는 connector 를 import
 * 하지 않는다.
 *
 * <p>계약은 {@code docs/features/assistant-hub/design.md} 의 「PR3」 이 갖는다.
 */
public interface ConnectorAttachRequests {
    /** 카드를 띄웠을 때의 {@code status} 값이다. */
    String REQUESTED = "approval_requested";
    /** 기본 비서에 이미 붙어 있을 때의 {@code status} 값이다. 카드는 없지만 실패가 아니다. */
    String ALREADY_ATTACHED = "already_attached";
    /** 그 연결이 붙은 전문 에이전트가 있을 때의 {@code status} 값이다. 카드 없이 그 에이전트에게 맡기게 한다. 실패가 아니다. */
    String SPECIALIST_ATTACHED = "specialist_attached";


    /**
     * @param originExecutionId 호출자가 검증한 현재 실행 번호. 카드는 그 실행의 대화에 뜬다
     * @param connectorId {@code connector_list} 의 {@code id}
     * @return {@code status} 와 {@code message}, 안내할 화면이 있으면 {@code link}. 플러그인의 이름은 넣지 않는다
     */
    Map<String, Object> request(CurrentUser user, Long originExecutionId, String connectorId);
}
