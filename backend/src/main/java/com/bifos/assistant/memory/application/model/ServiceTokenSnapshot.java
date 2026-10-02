package com.bifos.assistant.memory.application.model;

import com.bifos.assistant.memory.domain.ServiceToken;
import java.util.List;

/** 서비스 토큰 한 줄과 그 토큰이 받는 collection 이다. 원문과 해시는 응답으로 내지 않는다. */
public record ServiceTokenSnapshot(ServiceToken token, List<ServiceTokenGrant> grants) {}
