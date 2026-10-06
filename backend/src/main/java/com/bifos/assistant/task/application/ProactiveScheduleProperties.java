package com.bifos.assistant.task.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** ADR-086의 사용자별 격리 실행 공간을 운영에서 확인한 뒤 ADR-085의 매일 깨우기 제한을 푸는 설정이다. */
@ConfigurationProperties(prefix = "assistant.proactive-check")
@Validated
public record ProactiveScheduleProperties(boolean isolatedExecutionEnabled) {}
