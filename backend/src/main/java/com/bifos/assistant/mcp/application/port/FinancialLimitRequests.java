package com.bifos.assistant.mcp.application.port;

import com.bifos.assistant.shared.auth.CurrentUser;
import java.util.Map;

/**
 * 에이전트가 주문 상한을 읽고 바꾸는 길이다(ADR-20261015 / financial-user-limits). 구현은 커넥터가 갖고 mcp 는 커넥터를 import 하지 않는다.
 *
 * <p>올리기는 승인 카드만 만들고 반영하지 않는다. 결과 값은 MCP 도구가 JSON 글로 감싸 낸다.
 */
public interface FinancialLimitRequests {

    /** 그 사용자의 상한과 오늘 쓴 금액이다. */
    Map<String, Object> read(CurrentUser user);


    /**
     * 상한 하나를 바꾼다. 낮추기와 같은 값은 바로 반영하고, 올리기는 승인 줄을 만들고, 최대치를 넘는 값은 거절한다.
     *
     * @param originExecutionId 호출자가 검증한 현재 실행 번호
     * @param name 상한의 API 이름
     * @param value 0 이상, 소수 6자리까지의 십진 글
     */
    Map<String, Object> change(CurrentUser user, Long originExecutionId, String name, String value);
}
