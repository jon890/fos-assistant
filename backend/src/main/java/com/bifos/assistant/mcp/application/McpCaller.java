package com.bifos.assistant.mcp.application;

import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.usage.domain.AgentExecution;

/**
 * MCP 도구 호출 하나의 요청자를 정한 결과다.
 *
 * @param user 이 호출을 그 사람의 권한으로 돌릴 사용자. 부모 실행의 사용자다
 * @param parent 서명한 뿌리 session 과 토큰의 profile 로 찾은 도는 부모 실행. 옛 토큰이면 null
 * @param context 서명을 확인한 {@code _fos_ctx}. 옛 토큰이면 null
 */
public record McpCaller(CurrentUser user, AgentExecution parent, McpCallContext context) {

    /** 로그에 적을 부모 실행 번호다. 옛 토큰이면 null 이다. */
    public Long executionId() {
        return parent == null ? null : parent.id();
    }
}
