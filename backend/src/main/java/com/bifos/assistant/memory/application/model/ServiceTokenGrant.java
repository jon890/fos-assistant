package com.bifos.assistant.memory.application.model;

/** 서비스 토큰이 받는 collection 하나와 그 collection 의 민감 허용이다(ADR-056). */
public record ServiceTokenGrant(String collection, boolean allowSensitive) {}
