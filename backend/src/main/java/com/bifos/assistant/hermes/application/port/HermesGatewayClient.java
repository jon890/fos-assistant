package com.bifos.assistant.hermes.application.port;

import java.time.Instant;
import java.util.Optional;

/** 대시보드 plugin 의 {@code GET /api/gateway} 로 공유 gateway 의 기동 시각을 읽는다(ADR-20261120 / connector-auto-apply). */
public interface HermesGatewayClient {

    /**
     * 공유 gateway 가 지금 프로세스로 뜬 시각이다. 정밀도는 1초다.
     *
     * <p>gateway 가 떠 있지 않거나, 대시보드에 닿지 못했거나, 옛 plugin 이라 404 거나, 답의 모양이 틀리면 비어 있다. 예외를 던지지 않는다.
     */
    Optional<Instant> startedAt();
}
