package com.bifos.assistant.usage.application;

import com.bifos.assistant.usage.domain.AgentExecution;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 한 사용자의 루트 실행 한 페이지와 그 목록에 붙일 부가 정보다.
 *
 * @param executions 번호 내림차순의 루트 실행
 * @param idsHavingChildren 목록의 실행 중 자식을 가진 것의 번호
 * @param conversationPublicIds 목록 실행의 대화 번호를 그 대화의 공개 식별자로 잇는 표. 대화가 없는 실행은 빠진다
 */
public record RootExecutionPage(
        List<AgentExecution> executions, Set<Long> idsHavingChildren, Map<Long, UUID> conversationPublicIds) {

    public RootExecutionPage {
        executions = List.copyOf(executions);
        idsHavingChildren = Set.copyOf(idsHavingChildren);
        conversationPublicIds = Map.copyOf(conversationPublicIds);
    }
}
