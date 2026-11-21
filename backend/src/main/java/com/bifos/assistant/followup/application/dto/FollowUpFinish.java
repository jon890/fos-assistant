package com.bifos.assistant.followup.application.dto;

import java.util.List;

/**
 * 에이전트가 할 일 하나를 끝내려 한 결과다(서비스 개념 스펙 REQ12).
 *
 * @param matches {@code DONE} 이면 끝낸 줄 하나, {@code AMBIGUOUS} 면 후보들, {@code NOT_FOUND} 면 비어 있다
 */
public record FollowUpFinish(Status status, List<FollowUpSnapshot> matches) {

    public enum Status {
        /** 하나를 찾아 끝냈다. */
        DONE,
        /** 여럿이 맞아 바꾸지 않았다. */
        AMBIGUOUS,
        /** 챙기는 할 일 가운데 맞는 것이 없다. */
        NOT_FOUND
    }
}
