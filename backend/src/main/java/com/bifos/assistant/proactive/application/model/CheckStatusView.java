package com.bifos.assistant.proactive.application.model;

import com.bifos.assistant.proactive.domain.ProactiveCheck;
import java.util.UUID;

/**
 * 에이전트 화면의 살펴보기 절이 읽는 상태다.
 *
 * @param conversationId 요청자의 점검 대화의 공개 식별자. 없으면 null
 * @param lastCheck 요청자가 그 에이전트로 연 마지막 살펴보기. 없으면 null
 */
public record CheckStatusView(CheckReadiness readiness, UUID conversationId, ProactiveCheck lastCheck) {}
