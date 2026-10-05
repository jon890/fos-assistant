package com.bifos.assistant.connector.domain;

import java.time.Instant;

/**
 * 한 대화에서 승인 줄의 결과를 가장 늦게 전한 시각이다. 집계 쿼리의 결과 줄을 Spring Data 가 이 인터페이스로 받는다.
 *
 * <p>JPQL 의 생성자 식은 문자열 안에 전체 이름을 요구해 checkstyle 의 {@code fullyQualifiedName} 규칙에 걸린다. 그 규칙의 예외
 * 목록을 늘리지 않으려고 record 대신 인터페이스 투영으로 받는다.
 */
public interface ActionDelivery {

    /** 대화 번호. */
    Long getConversationId();

    /** 그 대화의 승인 줄 가운데 가장 늦은 {@code result_delivered_at}. */
    Instant getDeliveredAt();
}
