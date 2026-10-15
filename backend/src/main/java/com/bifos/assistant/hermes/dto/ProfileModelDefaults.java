package com.bifos.assistant.hermes.dto;

/** profile의 설정 기본값이다. provider가 실제로 적용한 강도는 아니다. */
public record ProfileModelDefaults(String provider, String model, String reasoningEffort) {
}
