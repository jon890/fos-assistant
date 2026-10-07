package com.bifos.assistant.feedback.application.model;

import com.bifos.assistant.feedback.domain.type.FeedbackEventType;

/**
 * 제안 하나의 사건을 읽은 결과다.
 *
 * @param label 첫 반응
 * @param wantsNow 「지금 이 제안을 원했는가」 의 표본 값. 반응이 없거나 보인 적이 없으면 null 이고 표본에서 뺀다
 * @param edited 사용자가 고친 적이 있다
 * @param outcome 마지막 실행 결과. 없으면 null
 * @param persistentPreference 오래 가는 선호의 근거인가. 사용자가 받아들인 Memory 제안만 참이다. 거절은 언제나 거짓이다
 */
public record SubjectLabel(
        FeedbackLabel label,
        Boolean wantsNow,
        boolean edited,
        FeedbackEventType outcome,
        boolean persistentPreference) {}
