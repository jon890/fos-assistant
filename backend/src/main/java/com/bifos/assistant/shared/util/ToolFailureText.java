package com.bifos.assistant.shared.util;

/**
 * 모델에게 돌려주는 도구 실패 글이다. 글은 끝 줄 하나로 끝난다(docs/features/tool-fallback/spec.md 의 REQ3).
 *
 * <p>모델이 해 볼 다른 길이 있으면 {@link #alternative} 로 「다음 방법: …」 을, 사용자가 정하거나 손을 써야 하는 실패면 {@link #askUser} 로
 * 「사용자에게 바로 알린다.」 를 붙인다. 실패 결과를 만드는 함수가 이 값만 받게 해서 끝 줄 없는 실패 글이 컴파일되지 않게 한다. fos-ctx 의
 * {@code next_step.py} 가 같은 두 끝 줄을 쓴다.
 */
public final class ToolFailureText {
    /** 다음 방법 줄의 머리다. */
    public static final String NEXT_PREFIX = "다음 방법: ";
    /** 바로 알림 줄이다. */
    public static final String ASK_USER = "사용자에게 바로 알린다.";

    private final String text;


    private ToolFailureText(String text) {
        this.text = text;
    }


    /** 모델이 해 볼 다른 길이 있는 실패다. 다른 길이 없으면 {@code next} 에 해 본 것을 알리고 묻는다고 쓴다. */
    public static ToolFailureText alternative(String failure, String next) {
        if (next == null || next.isBlank()) {
            throw new IllegalArgumentException("다음 방법이 비었다");
        }
        return new ToolFailureText(failure.stripTrailing() + "\n" + NEXT_PREFIX + next);
    }


    /** 다른 방법을 찾지 않고 바로 사용자에게 알릴 실패다. */
    public static ToolFailureText askUser(String failure) {
        return new ToolFailureText(failure.stripTrailing() + "\n" + ASK_USER);
    }


    public String text() {
        return text;
    }


    /** 마지막 줄이다. JSON 으로 돌려주는 실패의 {@code next} 칸이 이 값을 쓴다. */
    public String endLine() {
        return text.substring(text.lastIndexOf('\n') + 1);
    }


    @Override
    public String toString() {
        return text;
    }
}
