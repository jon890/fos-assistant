package com.bifos.assistant.shared.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param jwtSecret HMAC secret shared with the web tier, which mints the caller's token after an
 *     OAuth sign-in. The browser never holds a Control Plane token.
 */
@Validated
@ConfigurationProperties(prefix = "assistant.auth")
public record AuthProperties(String jwtSecret) {
}
