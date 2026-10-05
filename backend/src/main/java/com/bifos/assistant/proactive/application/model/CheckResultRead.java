package com.bifos.assistant.proactive.application.model;

import com.bifos.assistant.proactive.domain.type.CheckInvalidReason;

/**
 * 살펴보기 답에서 결과 블록을 읽은 결과다. 읽었으면 {@code block} 만, 읽지 못했으면 {@code invalidReason} 만 있다.
 *
 * @param block 읽은 블록. 읽지 못했으면 null 이다
 * @param invalidReason 읽지 못한 까닭. 읽었으면 null 이다
 */
public record CheckResultRead(CheckResultBlock block, CheckInvalidReason invalidReason) {

    public static CheckResultRead of(CheckResultBlock block) {
        return new CheckResultRead(block, null);
    }

    public static CheckResultRead invalid(CheckInvalidReason reason) {
        return new CheckResultRead(null, reason);
    }
}
