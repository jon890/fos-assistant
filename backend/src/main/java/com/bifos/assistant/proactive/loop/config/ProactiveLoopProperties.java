package com.bifos.assistant.proactive.loop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 매일 루프의 설치 설정이다.
 *
 * @param enabled 설치가 매일 루프를 연다. 기본은 꺼짐이다
 */
@ConfigurationProperties(prefix = "assistant.proactive-loop")
@Validated
public record ProactiveLoopProperties(@DefaultValue("false") boolean enabled) {
}
