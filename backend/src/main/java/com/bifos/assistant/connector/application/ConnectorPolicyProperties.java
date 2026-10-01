package com.bifos.assistant.connector.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 커넥터 도구 호출 판정의 설정이다(ADR-047).
 *
 * @param catalogTtl 판정이 쓰는 카탈로그를 메모리에 두는 시간
 */
@Validated
@ConfigurationProperties(prefix = "assistant.connector.policy")
public record ConnectorPolicyProperties(@DefaultValue("60s") Duration catalogTtl) {

    /** 0 이하이면 기동을 멈춘다. 판정마다 대시보드를 읽게 되는데 기동은 성공해 알아채지 못한다. */
    public ConnectorPolicyProperties {
        if (catalogTtl == null || catalogTtl.isZero() || catalogTtl.isNegative()) {
            throw new IllegalStateException(
                    "assistant.connector.policy.catalog-ttl must be longer than zero: " + catalogTtl);
        }
    }
}
