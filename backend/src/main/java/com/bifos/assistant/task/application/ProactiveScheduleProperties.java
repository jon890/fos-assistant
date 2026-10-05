package com.bifos.assistant.task.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** #190의 사용자별 격리 실행 공간이 운영에 반영됐는지를 알리는 설정이다. */
@ConfigurationProperties(prefix = "assistant.proactive-check")
@Validated
public record ProactiveScheduleProperties(boolean isolatedExecutionEnabled) {}
