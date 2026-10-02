package com.bifos.assistant.usage.application;

import com.bifos.assistant.usage.domain.AgentExecution;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 사용자의 루트 실행 목록을 읽는다.
 *
 * <p>트랜잭션을 열지 않는다. 세 조회가 각자 자기 트랜잭션으로 돈다.
 */
@Service
@RequiredArgsConstructor
public class RootExecutionQuery {

    private final AgentExecutionRepository executions;
    private final ConversationPublicIds conversations;

    /** 가장 최근 루트 실행을 {@code size} 개까지 읽고, 자식 여부와 대화의 공개 식별자를 붙인다. */
    public RootExecutionPage page(Long userId, int size) {
        List<AgentExecution> page =
                executions.findByUserIdAndRootExecutionIdIsNullOrderByIdDesc(userId, PageRequest.of(0, size));
        return new RootExecutionPage(page, idsHavingChildren(page), conversationPublicIds(page));
    }

    /**
     * 목록의 대화 번호를 공개 식별자로 한 번에 바꾼다.
     *
     * <p>실행마다 읽으면 질의가 목록 길이만큼 늘어난다. 대화 번호가 하나도 없으면 부르지 않는다.
     */
    private Map<Long, UUID> conversationPublicIds(List<AgentExecution> page) {
        Set<Long> ids = page.stream()
                .map(AgentExecution::conversationId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return conversations.publicIdsOf(ids);
    }

    /**
     * 목록의 실행 중 자식을 가진 것을 한 번에 읽는다.
     *
     * <p>실행마다 세면 질의가 목록 길이만큼 늘어난다. 목록이 비면 부르지 않는다. 빈 {@code in} 절은
     * 데이터베이스마다 다르게 동작한다.
     */
    private Set<Long> idsHavingChildren(List<AgentExecution> page) {
        if (page.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(executions.findParentIdsHavingChildren(
                page.stream().map(AgentExecution::id).toList()));
    }
}
