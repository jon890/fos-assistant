package com.bifos.assistant.mcp.application.port;

import com.bifos.assistant.shared.auth.CurrentUser;
import java.util.Map;

/**
 * 에이전트가 요청자의 기억 하나를 지워 달라고 청하는 길이다(스펙 service-concepts REQ13). 지우지 않고 승인 카드만 띄운다. 구현은 승인
 * 줄을 가진 {@code connector} 가 갖고 mcp 는 connector 를 import 하지 않는다.
 *
 * <p>계약은 {@code docs/features/service-concepts/design.md} 의 「PR3 할 일과 기억 도구」 가 갖는다.
 */
public interface MemoryForgetRequests {
    /** 카드를 띄웠을 때의 {@code status} 값이다. 다른 값이면 띄우지 못한 것이다. */
    String REQUESTED = "approval_requested";


    /**
     * @param originExecutionId 호출자가 검증한 현재 실행 번호. 카드는 그 실행의 대화에 뜬다
     * @return {@code status} 와 {@code message}. 기억의 제목과 내용은 넣지 않는다
     */
    Map<String, Object> request(CurrentUser user, Long originExecutionId, Long memoryId);
}
