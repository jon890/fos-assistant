package com.bifos.assistant.hermes;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param profileKeyDir directory holding one mode-600 file per profile, named after the profile and
 *     containing that profile's {@code API_SERVER_KEY}
 * @param pollInterval delay between run status polls
 * @param runTimeout how long a single run may stay unfinished before we give up waiting
 * @param connectTimeout TCP connect budget for one call to Hermes
 * @param readTimeout response budget for one call to Hermes; submitting and polling both return at
 *     once, so this stays far below {@code runTimeout}
 */
@ConfigurationProperties(prefix = "hermes")
public record HermesProperties(
        String profileKeyDir,
        Duration pollInterval,
        Duration runTimeout,
        Duration connectTimeout,
        Duration readTimeout) {

    public HermesProperties {
        pollInterval = pollInterval == null ? Duration.ofMillis(700) : pollInterval;
        runTimeout = runTimeout == null ? Duration.ofMinutes(5) : runTimeout;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(30) : readTimeout;
    }
}
