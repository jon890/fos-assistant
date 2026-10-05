package com.bifos.assistant.followup.application.model;

import java.time.Instant;

/**
 * 할 일 고치기의 입력이다.
 *
 * @param title 새 제목. null 이면 그대로 둔다
 * @param dueAtPresent 거짓이면 기한을 그대로 두고, 참이면 {@code dueAt} 으로 바꾼다
 * @param dueAt 새 기한. {@code dueAtPresent} 가 참일 때 null 이면 기한을 지운다
 * @param waiting 새 기다리는 중. null 이면 그대로 둔다
 */
public record FollowUpPatch(String title, boolean dueAtPresent, Instant dueAt, Boolean waiting) {

    /** 제목을 빼고 낸다. 제목을 로그에 남기지 않는다. */
    @Override
    public String toString() {
        return "FollowUpPatch[titlePresent=" + (title != null) + ", dueAtPresent=" + dueAtPresent + ", dueAt=" + dueAt
                + ", waiting=" + waiting + "]";
    }
}
