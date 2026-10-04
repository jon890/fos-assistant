package com.bifos.assistant.chat.application.model;

import com.bifos.assistant.context.ContextItem;
import java.util.List;

/**
 * 결과를 전하는 turn 의 Hermes 입력과 그 입력에 실은 결과 항목이다. 자동 turn 과 다시 전달이 함께 쓴다.
 *
 * <p>{@code input} 에 결과 본문이 들어 있어 {@link #toString()} 은 글자 수와 항목 수만 낸다.
 *
 * @param items 입력에 실은 순서의 결과 항목. 위임 결과가 먼저이고 그 뒤로 그 밖의 결과다
 */
public record DeliveryInput(String input, List<ContextItem> items) {

    public DeliveryInput {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** 본문이 로그에 남지 않게 글자 수와 항목 수만 낸다. */
    @Override
    public String toString() {
        return "DeliveryInput[chars=" + input.length() + ", items=" + items.size() + "]";
    }
}
