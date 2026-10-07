package com.bifos.assistant.feedback.domain.type;

/**
 * 사건이 가리키는 제안의 종류와 그 열쇠의 머리다. 열쇠는 지금 화면의 {@code itemKey} 와 같은 모양이라 {@code attention_event} 와
 * 같은 항목으로 이어 읽을 수 있다.
 */
public enum FeedbackSubjectType {
    /** 에이전트가 제안한 할 일. 열쇠는 {@code follow_up:<공개 식별자>} 다. */
    FOLLOW_UP("follow_up"),
    /** 에이전트가 제안한 개인 Memory. 열쇠는 {@code memory:<번호>} 다. */
    MEMORY("memory"),
    /** 커넥터 쓰기의 승인 줄. 열쇠는 {@code connector_action:<공개 식별자>} 다. */
    CONNECTOR_ACTION("connector_action"),
    /** 먼저 살펴보기 한 번. 보고를 보였거나 자동 실행이 끝났다. 열쇠는 {@code proactive_check:<번호>} 다. */
    CHECK("proactive_check"),
    /** 행동 정책의 판정 하나. 자동 실행을 시작하지 못했다. 열쇠는 {@code autonomy_decision:<번호>} 다. */
    AUTONOMY_DECISION("autonomy_decision");

    private final String prefix;

    FeedbackSubjectType(String prefix) {
        this.prefix = prefix;
    }

    public String key(Object id) {
        return prefix + ":" + id;
    }

    /** 열쇠의 머리로 종류를 찾는다. 모르는 머리면 null 이다. */
    public static FeedbackSubjectType ofKey(String subjectKey) {
        if (subjectKey == null) {
            return null;
        }
        for (FeedbackSubjectType type : values()) {
            if (subjectKey.startsWith(type.prefix + ":")) {
                return type;
            }
        }
        return null;
    }
}
