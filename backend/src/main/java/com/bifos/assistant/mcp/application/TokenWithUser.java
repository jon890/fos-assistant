package com.bifos.assistant.mcp.application;

import com.bifos.assistant.mcp.domain.AgentToken;

/**
 * 목록에 보일 토큰 한 줄이다.
 *
 * @param userEmail profile 이 빈 옛 토큰을 발급한 사용자의 메일. profile 이 묶인 토큰이면 null 이다
 */
public record TokenWithUser(AgentToken token, String userEmail) {}
