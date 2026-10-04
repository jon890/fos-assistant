package com.bifos.assistant.chat.application;

import com.bifos.assistant.context.AssembledContext;
import com.bifos.assistant.usage.application.ContextSourceRef;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 문맥 묶음의 항목을 실행 기록에 넘길 참조로 옮긴다.
 *
 * <p>{@code usage} 는 {@code context} 를 쓰지 못해(ADR-068) 이름을 문자열로 옮겨 넘긴다. 제목과 본문은 옮기지 않는다(ADR-071).
 * 대화 turn 과 {@code orchestration} 의 실행이 함께 쓴다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ContextSourceRefs {

    /** 묶음에 실은 순서 그대로 옮긴다. 묶음이 비면 빈 목록이다. */
    public static List<ContextSourceRef> of(AssembledContext context) {
        return context.bundle().items().stream()
                .map(item -> new ContextSourceRef(
                        item.source().name(),
                        item.ref(),
                        item.bodyMode().name(),
                        item.freshness().name()))
                .toList();
    }
}
