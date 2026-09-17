package com.bifos.assistant.usage.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param catalogPath models.dev 카탈로그 파일의 경로. 카탈로그를 붙이지 않았으면 비어 있다. Hermes
 *     profile 디렉터리를 직접 가리키지 않는다. 그 디렉터리에는 {@code .env} 와 {@code auth.json} 이
 *     함께 있어서, 배포할 때 카탈로그만 중립 경로로 복사하고 그쪽을 읽기 전용으로 붙인다.
 */
@ConfigurationProperties(prefix = "assistant.pricing")
public record PricingProperties(String catalogPath) {
}
