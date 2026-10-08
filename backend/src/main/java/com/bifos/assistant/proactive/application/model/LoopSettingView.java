package com.bifos.assistant.proactive.application.model;

import java.time.Instant;

/**
 * 에이전트 하나의 매일 루프 설정이다.
 *
 * @param available 설치가 매일 루프를 연다. 거짓이면 켤 수 없다
 * @param enabled 사용자가 켰다. 설정 줄이 없으면 거짓이다
 * @param snoozedUntil 쉬는 끝 시각. 지금보다 뒤일 때만 싣고 아니면 null
 */
public record LoopSettingView(boolean available, boolean enabled, Instant snoozedUntil) {}
