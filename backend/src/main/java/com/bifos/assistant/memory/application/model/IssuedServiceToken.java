package com.bifos.assistant.memory.application.model;

/** 방금 발급한 서비스 토큰이다. 원문은 이때 한 번만 나온다. 저장하는 것은 해시뿐이다. */
public record IssuedServiceToken(ServiceTokenSnapshot snapshot, String rawToken) {}
