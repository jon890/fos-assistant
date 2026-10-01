package com.bifos.assistant.connector.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 커넥터 도구 호출 판정과 승인의 설정이다(ADR-049, ADR-050).
 *
 * @param catalogTtl 판정이 쓰는 카탈로그를 메모리에 두는 시간
 * @param catalogFailureTtl 카탈로그 읽기 실패를 기억하는 시간. 그동안은 대시보드를 다시 부르지 않고 거절한다
 * @param approvalTtl 승인 요청이 사용자의 답을 기다리는 시간. 지나면 실행하지 않고 만료로 둔다
 * @param expireCron 답이 없는 승인 요청을 만료로 바꾸는 주기. 일정이 이 값을 설정 이름으로 읽는다. {@code -} 이면 돌지 않는다
 */
@Validated
@ConfigurationProperties(prefix = "assistant.connector.policy")
public record ConnectorPolicyProperties(
        @DefaultValue("60s") Duration catalogTtl,
        @DefaultValue("5s") Duration catalogFailureTtl,
        @DefaultValue("24h") Duration approvalTtl,
        @DefaultValue("0 * * * * *") String expireCron) {

    /** 시간 가운데 하나라도 0 이하이면 기동을 멈춘다. 판정마다 대시보드를 읽거나 승인 요청이 만들자마자 만료되는데 기동은 성공해 알아채지 못한다. */
    public ConnectorPolicyProperties {
        requirePositive("catalog-ttl", catalogTtl);
        requirePositive("catalog-failure-ttl", catalogFailureTtl);
        requirePositive("approval-ttl", approvalTtl);
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(
                    "assistant.connector.policy." + name + " must be longer than zero: " + value);
        }
    }
}
