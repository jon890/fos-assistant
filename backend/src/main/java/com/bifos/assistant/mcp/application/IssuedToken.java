package com.bifos.assistant.mcp.application;

import com.bifos.assistant.mcp.domain.AgentToken;

/**
 * 토큰을 새로 발급한 결과다.
 *
 * <p>{@code rawToken} 은 이때 한 번만 나온다. 저장하는 것은 그 해시뿐이라 다시 꺼낼 수 없다.
 */
public record IssuedToken(AgentToken token, String userEmail, String rawToken) {}
