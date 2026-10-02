package com.bifos.assistant.memory.application.model;

import java.time.Instant;

/** 서비스 토큰이 증명한 요청자다. 사용자 한 사람과 그 토큰이 받는 collection 이다(ADR-056). */
public record ServicePrincipal(Long tokenId, Long userId, MemoryAccess access, Instant expiresAt) {}
