package com.bifos.assistant.memory.maintenance.application;

import com.bifos.assistant.memory.application.MemoryVectorCache;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.infra.MemoryRevisionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 정리 때 그 사용자의 개인 기억을 지운다(ADR-20261101 / user-purge).
 *
 * <p>개인 기억({@code scope = 'USER'})과 그 벡터, 그 사용자가 주인이던 변경 이력을 지운다. 그룹 공용 기억은
 * 주인이 비어 있어 건드리지 않는다. 벡터는 항목 줄의 칸이라 줄과 함께 지워진다. 기억한 기록({@code memory_capture})은 본문 없이 남긴다. 부르는 쪽의 정리 트랜잭션 안에서만 돈다.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class MemoryUserPurge {

    private final MemoryRepository memories;
    private final MemoryRevisionRepository revisions;
    private final MemoryVectorCache vectorCache;


    /**
     * 그 사용자의 개인 기억을 지운다. 지울 것이 없으면 아무것도 하지 않으므로 다시 불러도 같다.
     *
     * @return 지운 기억 항목 수
     */
    public int purgeUser(Long userId) {
        List<Long> ids = memories.findIdsByOwnerAndScope(userId, MemoryScope.USER);
        revisions.deleteAllOfOwner(userId);
        if (ids.isEmpty()) {
            return 0;
        }
        int deleted = memories.deleteAllByIds(ids);
        ids.forEach(vectorCache::evict);
        return deleted;
    }
}
