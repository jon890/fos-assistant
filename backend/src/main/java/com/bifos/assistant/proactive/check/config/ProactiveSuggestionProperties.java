package com.bifos.assistant.proactive.check.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 먼저 살펴보기의 제안 저장 경로 스위치다(ADR-20261101 / proactive-suggestion).
 *
 * @param enabled 켜면 결과 블록의 제안 항목을 할 일의 {@code PROPOSED} 줄로 저장한다. 기본은 꺼짐이고, 꺼져 있으면 발견과 문제 후보만
 * 지금처럼 저장한다
 */
@ConfigurationProperties(prefix = "assistant.proactive-suggestion")
@Validated
public record ProactiveSuggestionProperties(@DefaultValue("false") boolean enabled) {
}
