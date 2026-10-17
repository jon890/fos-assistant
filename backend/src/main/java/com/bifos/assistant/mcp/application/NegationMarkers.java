package com.bifos.assistant.mcp.application;

import com.bifos.assistant.memory.domain.MemoryNegation;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * {@code memory_remember} 의 바로 저장 판정이 보는 부정 표지다(ADR-20261008 / memory-remember-guard). 질문 원문과 본문 가운데 한쪽에만
 * 부정이 있으면 모델이 뜻을 뒤집었다고 보고 제안으로 내린다. 규칙은 대체 후보 탐지와 같아 {@link MemoryNegation} 이 갖는다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class NegationMarkers {

    /** 글에 부정 표지가 있는가(ADR-20261008 / memory-remember-guard). */
    static boolean present(String text) {
        return MemoryNegation.present(text);
    }
}
