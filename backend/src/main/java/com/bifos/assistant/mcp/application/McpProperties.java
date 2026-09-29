package com.bifos.assistant.mcp.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Control Plane MCP 의 설정이다.
 *
 * @param legacyUserTokens profile 이 빈 옛 토큰을 그 토큰의 사용자로 돌릴지. 옮겨 가는 동안에만 참으로 둔다.
 *     거짓이면 그런 토큰은 인증에서 거절된다(ADR-032)
 */
@ConfigurationProperties(prefix = "assistant.mcp")
public record McpProperties(boolean legacyUserTokens) {
}
