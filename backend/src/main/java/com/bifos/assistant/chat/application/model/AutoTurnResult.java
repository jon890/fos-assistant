package com.bifos.assistant.chat.application.model;

import com.bifos.assistant.context.ContextItem;

/**
 * 자동 turn 으로 전할 결과 하나다.
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
}
