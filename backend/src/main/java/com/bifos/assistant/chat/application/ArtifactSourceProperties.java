package com.bifos.assistant.chat.application;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 결과물 주소 다운로드에 허용할 호스트와 제한 시간을 정한다. */
@Validated
@ConfigurationProperties(prefix = "assistant.artifact.source")
public record ArtifactSourceProperties(
        List<String> allowedHosts, Duration connectTimeout, Duration readTimeout, Duration totalTimeout) {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_TOTAL_TIMEOUT = Duration.ofSeconds(30);

    public ArtifactSourceProperties {
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        for (String host : allowedHosts) {
            if (host == null || host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")
                    || !host.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+")
                    || !host.equals(host.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("assistant.artifact.source.allowed-hosts must contain lowercase ASCII DNS names");
            }
        }
        connectTimeout = positive(connectTimeout, DEFAULT_CONNECT_TIMEOUT, "connect-timeout");
        readTimeout = positive(readTimeout, DEFAULT_READ_TIMEOUT, "read-timeout");
        totalTimeout = positive(totalTimeout, DEFAULT_TOTAL_TIMEOUT, "total-timeout");
    }

    private static Duration positive(Duration value, Duration defaultValue, String name) {
        Duration result = value == null ? defaultValue : value;
        if (result.isZero() || result.isNegative()) {
            throw new IllegalStateException("assistant.artifact.source." + name + " must be positive");
        }
        return result;
    }
}
