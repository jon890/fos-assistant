package com.bifos.assistant.proactive.application;

import com.bifos.assistant.shared.auth.CurrentUser;

/**
 * 살펴보기 turn 의 끝을 정리하고 점검 대화 잠금을 푼 뒤에 낸다. turn 이 어떻게 끝났든, 끝 정리와 잠금 풀기가 예외 없이 끝났고 살펴보기 줄이
 * 저장됐으면 낸다.
 *
 * <p>{@link ProactiveLoopCoordinator} 가 같은 스레드에서 받아 매일 루프를 잇는다(ADR-20261008 / daily-loop).
 * {@code AutonomyPolicyService} 가 {@link ProactiveCheckService} 를 부르므로, 반대 방향은 직접 부르지 않고 이 사건으로 잇는다.
 *
 * @param user 살펴보기를 연 사용자
 * @param checkId 끝난 살펴보기
 */
public record ProactiveCheckSettled(CurrentUser user, Long checkId) {}
