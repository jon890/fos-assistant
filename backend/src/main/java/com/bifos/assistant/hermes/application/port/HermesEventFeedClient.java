package com.bifos.assistant.hermes.application.port;

import com.bifos.assistant.hermes.application.dto.ConnectorEventListing;
import java.time.Instant;

/** 대시보드 plugin 의 사건 감지 목록 조회 경로를 부른다(ADR-20261105 / event-watch). */
public interface HermesEventFeedClient {

    /**
     * 그 profile 에 설치한 연결의 값으로 사건 감지 조회 도구를 불러 {@code since} 뒤의 항목을 받는다.
     *
     * <p>항목마다 식별자, 받은 시각, 라벨만 온다. 대시보드가 실패를 공통 어휘로 답하면 실패 결과이고, 닿지 못했거나 답의 모양이 틀리면
     * {@code unavailable} 이다. 예외를 던지지 않는다.
     *
     * @param since 이 시각 뒤에 받은 항목만 청한다. 초 단위로 내린다
     */
    ConnectorEventListing listEvents(String connectorId, String profile, Instant since);
}
