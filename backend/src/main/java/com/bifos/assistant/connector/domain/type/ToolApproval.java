package com.bifos.assistant.connector.domain.type;

/**
 * 커넥터 도구를 부르기 전에 승인을 받는 방식이다(ADR-047).
 *
 * <p>선언 순서가 느슨한 것에서 엄격한 것의 순서다. 하한 비교가 이 순서를 쓰므로 값을 끼워 넣거나 순서를 바꾸지
 * 않는다. 뜻은 {@code docs/connectors.md} 의 「도구 정책」 이 갖는다.
 */
public enum ToolApproval {
    NONE,
    REQUIRED,
    ALWAYS;

    /** 이 방식이 {@code other} 보다 느슨한가. 같으면 거짓이다. */
    public boolean looserThan(ToolApproval other) {
        return ordinal() < other.ordinal();
    }

    /** manifest 가 쓰는 소문자 글을 읽는다. 모르는 글과 null 은 null 이다. */
    public static ToolApproval fromWord(String word) {
        if (word == null) {
            return null;
        }
        return switch (word) {
            case "none" -> NONE;
            case "required" -> REQUIRED;
            case "always" -> ALWAYS;
            default -> null;
        };
    }
}
