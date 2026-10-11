package com.bifos.assistant.memory.application;

import com.bifos.assistant.memory.application.model.MemoryAccess;
import com.bifos.assistant.memory.application.model.MemorySearchItem;
import com.bifos.assistant.memory.application.model.MemorySearchPage;
import com.bifos.assistant.memory.infra.MemoryQueries;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/** 기억 제목의 입력 검사와 권한을 반영한 조회를 맡는다. MemoryService의 검색 트랜잭션 안에서 실행한다. */
@Component
@RequiredArgsConstructor
class MemoryTitleSearch {

    private static final Sort BY_ID = Sort.by("id");

    private final MemoryRepository memories;


    MemorySearchPage searchFor(CurrentUser user, MemoryAccess access, String query, int limit, Long afterId) {
        if (query == null
            || query.strip().isEmpty()
            || query.strip().codePointCount(0, query.strip().length()) > 200
            || limit < 1
            || limit > 50
            || (afterId != null && afterId <= 0)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "invalid memory search arguments");
        }
        if (access.isEmpty()) {
            return new MemorySearchPage(List.of(), null);
        }
        List<MemorySearchItem> found = memories.findBy(
            MemoryQueries.searchFor(
                user.id(),
                user.groupId(),
                access.unrestricted() ? null : access.collections(),
                access.sensitiveCollections(),
                query,
                afterId),
            selected -> selected.as(MemorySearchItem.class)
                .sortBy(BY_ID)
                .limit(limit + 1)
                .all());
        boolean more = found.size() > limit;
        List<MemorySearchItem> items = List.copyOf(found.subList(0, Math.min(limit, found.size())));
        return new MemorySearchPage(items, more ? items.getLast().id() : null);
    }
}
