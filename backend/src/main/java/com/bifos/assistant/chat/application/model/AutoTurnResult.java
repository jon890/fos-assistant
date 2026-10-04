package com.bifos.assistant.chat.application.model;

import com.bifos.assistant.context.ContextItem;

/**
 * 자동 turn 으로 전할 결과 하나다.
 *
 * <p>{@code input} 에 결과 본문이, {@code notice} 에 결과를 가리키는 글이 들어 있어 {@link #toString()} 은 {@code key} 와 글자
 * 수와 항목의 출처와 참조만 낸다.
 *
 * @param key 그 결과를 낸 쪽이 전했다고 적을 때 쓰는 이름
 * @param notice 대화에 남기는 알림 줄의 글
 * @param input Hermes 입력에 넣을 단락. 본문은 여기에만 싣고 알림 줄에는 싣지 않는다
 * @param item 그 결과의 문맥 항목(ADR-071). 제목과 본문은 비어 있다. 비어 있으면 그 결과의 참조를 실행에 남기지 않는다
 */
public record AutoTurnResult(String key, String notice, String input, ContextItem item) {

    /** 문맥 항목이 없는 결과다. 실행에 참조를 남기지 않는다. */
    public AutoTurnResult(String key, String notice, String input) {
        this(key, notice, input, null);
    }

    /** 본문과 알림 줄의 글이 로그에 남지 않게 key 와 글자 수와 항목의 출처와 참조만 낸다. */
    @Override
    public String toString() {
        return "AutoTurnResult[key=" + key
                + ", noticeChars=" + (notice == null ? 0 : notice.length())
                + ", inputChars=" + (input == null ? 0 : input.length())
                + ", item=" + (item == null ? "null" : item.source() + ":" + item.ref())
                + "]";
    }
}
