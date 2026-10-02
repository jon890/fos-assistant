package com.bifos.assistant.mcp.application;

import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.domain.AgentExecution;
import java.util.Objects;

/**
 * MCP 도구 호출 하나의 요청자를 정한 결과다.
 *
 * <p>셋은 늘 있다. 사용자가 걸린 MCP 호출은 이 셋 없이 도구에 닿지 못한다.
 *
 * @param user 이 호출을 그 사람의 권한으로 돌릴 사용자. origin 실행의 사용자다
 * @param originExecution 이 호출의 session 을 낳은 FOS 실행. 끝난 실행일 수 있지만 그 실행이나 루트 실행이 {@code CANCELLED} 인 것은 아니다
 * @param context 서명을 확인한 {@code _fos_ctx}
 */
public record McpCaller(CurrentUser user, AgentExecution originExecution, McpCallContext context) {

    public McpCaller {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(originExecution, "originExecution");
        Objects.requireNonNull(context, "context");
    }

    /** 로그에 적을 origin 실행 번호다. */
    public Long executionId() {
        return originExecution.id();
    }
}
