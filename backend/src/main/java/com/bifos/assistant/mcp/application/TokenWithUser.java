package com.bifos.assistant.mcp.application;

import com.bifos.assistant.mcp.domain.AgentToken;

/**
 * 토큰과 그 토큰이 가리키는 사용자의 메일 주소다.
 */
public record TokenWithUser(AgentToken token, String userEmail) {}
