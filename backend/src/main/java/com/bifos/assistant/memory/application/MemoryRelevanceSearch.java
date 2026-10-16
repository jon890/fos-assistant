package com.bifos.assistant.memory.application;

import com.bifos.assistant.memory.application.model.MemoryAccess;
import com.bifos.assistant.memory.application.model.MemorySearchCursor;
import com.bifos.assistant.memory.application.model.MemorySearchItem;
import com.bifos.assistant.memory.application.model.MemorySearchPage;
import com.bifos.assistant.memory.application.relevance.MemoryRelevance;
import com.bifos.assistant.memory.application.relevance.RelevanceCandidate;
import com.bifos.assistant.memory.application.relevance.RelevanceResult;
import com.bifos.assistant.memory.domain.MemorySearchCandidate;
import com.bifos.assistant.memory.infra.MemorySearchCandidates;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 기억을 제목과 일반 본문의 관련도 순으로 찾는다(ADR-20261015 / memory-hybrid-selection). {@link MemoryService} 의 검색 트랜잭션 안에서 돈다.
 *
 * <p>권한 조건만 건 후보를 모두 읽어 점수를 매기고 커서 위치부터 자른다. 질문 벡터는 부르는 쪽이 이 트랜잭션 밖에서 미리 만든다. 앞에서 {@link MemorySearchCursor#MAX_RANK} 번째까지만 낸다.
 */
@Component
@RequiredArgsConstructor
class MemoryRelevanceSearch {

    private final MemorySearchCandidates candidates;
    private final MemoryRelevance relevance;


    MemorySearchPage searchFor(
        CurrentUser user, MemoryAccess access, String query, int limit, MemorySearchCursor cursor, Optional<float[]> queryVector) {
        String stripped = query == null ? "" : query.strip();
        if (stripped.isEmpty()
            || stripped.codePointCount(0, stripped.length()) > 200
            || limit < 1
            || limit > 50
            || cursor == null
            || !cursor.matches(stripped)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "invalid memory search arguments");
        }
        if (access.isEmpty()) {
            return new MemorySearchPage(List.of(), null, queryVector.isEmpty() ? MemoryRelevance.SEARCH_KEYWORD : MemoryRelevance.SEARCH_HYBRID);
        }
        List<MemorySearchCandidate> found = candidates.candidatesFor(
            user.id(), user.groupId(), access.unrestricted() ? null : access.collections(), access.sensitiveCollections());
        RelevanceResult result = relevance.score(
            stripped,
            queryVector,
            found.stream().map(each -> new RelevanceCandidate(each.id(), each.title(), each.plainContent())).toList());
        Map<Long, MemorySearchCandidate> byId = found.stream()
            .collect(Collectors.toMap(MemorySearchCandidate::id, Function.identity()));
        int ranked = Math.min(result.ranked().size(), MemorySearchCursor.MAX_RANK);
        int start = Math.min(cursor.offset(), ranked);
        int end = Math.min(start + limit, ranked);
        List<MemorySearchItem> items = result.ranked().subList(start, end).stream()
            .map(score -> byId.get(score.memoryId()))
            .map(each -> new MemorySearchItem(each.id(), each.title(), each.revision(), each.updatedAt()))
            .toList();
        String next = end < ranked ? new MemorySearchCursor(cursor.queryFingerprint(), end).encode() : null;
        return new MemorySearchPage(items, next, result.selector());
    }
}
