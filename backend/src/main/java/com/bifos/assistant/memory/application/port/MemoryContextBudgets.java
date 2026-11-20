package com.bifos.assistant.memory.application.port;

import com.bifos.assistant.memory.application.dto.MemoryContextBudget;

/**
 * 그룹마다 기억 조립 예산을 낸다. 운영 구현은 {@code MemoryContextSettings} 이고, 조립기를 직접 만드는 테스트는 람다로 넘긴다.
 */
@FunctionalInterface
public interface MemoryContextBudgets {

    /**
     * @param groupId 요청자의 그룹. 그룹이 없는 사용자면 null 이다
     */
    MemoryContextBudget budgetFor(Long groupId);
}
