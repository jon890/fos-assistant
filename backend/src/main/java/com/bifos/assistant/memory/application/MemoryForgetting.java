package com.bifos.assistant.memory.application;

import com.bifos.assistant.memory.application.dto.MemoryForgetOutcome;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.type.MemoryEntryType;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemoryStatus;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 에이전트가 청한 기억 지우기의 대상을 정하고, 사용자가 승인하면 지운다(서비스 개념 스펙 REQ13).
 *
 * <p>대상은 기억 화면의 「알고 있는 것」({@code ACCEPTED})과 「검토할 기억」({@code PROPOSED})이다. 문서와 출처 원문은 다루지 않는다
 * (스펙 DECISION25). 권한은 화면의 지우기와 같다. 개인 기억은 주인만, 그룹 기억은 {@code ADMIN} 만 지운다.
 */
@Service
@RequiredArgsConstructor
public class MemoryForgetting {
    private final MemoryRepository memories;
    private final MemoryRevisionWriter revisionWriter;


    /**
     * 지우기를 청할 수 있는 항목을 읽는다. 승인 카드를 띄우기 전에 부른다.
     *
     * @throws ApiException 없거나 남의 것이거나 문서면 MEMORY_NOT_FOUND, 그룹 기억인데 관리자가 아니면 FORBIDDEN
     */
    @Transactional(readOnly = true)
    public Memory target(CurrentUser user, Long id) {
        Memory memory = memories.findById(id).filter(found -> listed(user, found)).orElseThrow(MemoryForgetting::notFound);
        if (!writable(user, memory)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "this action is limited to the group admin");
        }
        return memory;
    }


    /**
     * 승인한 지우기를 한다. 화면의 지우기처럼 마지막 값을 버전으로 남기고 줄을 지운다.
     *
     * <p>승인 줄을 잠근 트랜잭션 안에서 돈다. 예외를 내면 그 트랜잭션이 되돌림으로 표시되므로, 그 사이 없어졌거나 권한이 바뀐 항목과
     * 카드를 띄운 뒤 고친 항목은 결과로 답한다.
     *
     * @param expectedRevision 카드에 보인 내용의 버전. 지금 버전과 다르면 사용자가 본 것과 지울 것이 달라 지우지 않는다
     */
    @Transactional
    public MemoryForgetOutcome forget(CurrentUser user, Long id, int expectedRevision) {
        Optional<Memory> found = memories.findByIdForUpdate(id).filter(memory -> listed(user, memory) && writable(user, memory));
        if (found.isEmpty()) {
            return MemoryForgetOutcome.GONE;
        }
        Memory memory = found.get();
        if (memory.revision() != expectedRevision) {
            return MemoryForgetOutcome.CHANGED;
        }
        revisionWriter.beforeDelete(user, memory);
        memories.delete(memory);
        return MemoryForgetOutcome.FORGOTTEN;
    }


    private static boolean listed(CurrentUser user, Memory memory) {
        return memory.isReadableBy(user.id(), user.groupId())
            && memory.entryType() == MemoryEntryType.MEMORY
            && memory.status() != MemoryStatus.REJECTED;
    }


    private static boolean writable(CurrentUser user, Memory memory) {
        return memory.scope() != MemoryScope.GROUP || user.isAdmin();
    }


    private static ApiException notFound() {
        return new ApiException(ErrorCode.MEMORY_NOT_FOUND, "no such memory");
    }
}
