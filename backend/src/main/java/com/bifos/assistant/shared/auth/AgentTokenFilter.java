package com.bifos.assistant.shared.auth;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * profile 토큰으로 인증하는 필터의 타입이다.
 *
 * <p>{@code SecurityConfig} 가 구현 패키지를 모른 채 필터 순서에 넣으려고 둔다. 구현은 {@code mcp.presentation} 에 있다.
 */
public abstract class AgentTokenFilter extends OncePerRequestFilter {}
