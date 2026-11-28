package com.bifos.assistant.memory.application;

import com.bifos.assistant.memory.application.dto.MemoryAccess;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.type.MemoryCaptureKind;
import com.bifos.assistant.memory.domain.type.MemoryEntryType;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemoryStatus;
import com.bifos.assistant.memory.infra.MemoryCaptureRepository;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 합치기에서 새 기억이 옛 기억을 대신하게 한다(docs/features/memory/spec.md 의 REQ6).
 *
 * <p>대신할 수 있는 옛 기억은 요청자의 승인된 개인 {@code MEMORY} 항목이고 풀리며 대체되지 않았고 받는 collection 에 있으며 새 기억 자신이 아니다. 그 밖의
 * 번호는 까닭을 가리지 않고 건너뛴다. 볼 수 없는 기억과 없는 기억을 같은 응답으로 답한다(스펙 DECISION27).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemoryReplacements {

    private final MemoryRepository memories;
    private final MemoryCaptureRepository captures;


    /**
     * 번호들 가운데 지금 대신할 수 있는 것을 번호 순으로 낸다. 잠그지 않고 읽는다. 쓰기는 {@link #supersede} 의 조건부 UPDATE 가 다시 판정한다.
     *
     * @param newId 새 기억 번호. 아직 저장하지 않았으면 null 이다
     */
    public List<Long> replaceable(CurrentUser user, MemoryAccess access, Collection<Long> ids, Long newId) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return memories.findAllById(ids).stream()
            .filter(memory -> replaceable(user, access, memory, newId))
            .map(Memory::id)
            .sorted()
            .toList();
    }


    /**
     * 새 기억이 대신할 수 있는 옛 기억을 대신하게 하고 실제로 대신한 번호를 번호 순으로 낸다. 새 기억의 저장과 같은 트랜잭션에서 부른다.
     *
     * <p>풀리는지는 SQL 이 볼 수 없어 먼저 읽어 제외한다. 나머지 조건은 UPDATE 가 다시 보므로 그 사이 바뀐 줄은 바뀌지 않는다.
     */
    @Transactional
    public List<Long> supersede(CurrentUser user, MemoryAccess access, Long newId, Collection<Long> ids) {
        List<Long> candidates = replaceable(user, access, ids, newId);
        if (candidates.isEmpty()) {
            return List.of();
        }
        if (access.unrestricted()) {
            memories.supersede(newId, candidates, user.id());
        } else {
            memories.supersedeWithin(newId, candidates, user.id(), access.collections());
        }
        return memories.findIdsSupersededBy(newId, candidates);
    }


    /**
     * 받아들인 제안이 합치기였으면 그 기록의 번호로 옛 기억을 대신하게 하고, 기록을 실제로 대신한 번호로 고친다. 대신하지 않은 제목이 「합쳤어요」 에 보이지
     * 않게 한다. 이 경로에는 에이전트의 collection 범위가 없어 주인의 범위로 본다(대신할 옛 기억도 주인 자신의 기억이다).
     */
    @Transactional
    public void onProposalAccepted(CurrentUser user, Long memoryId) {
        captures.findFirstByMemoryIdAndKindOrderByIdDesc(memoryId, MemoryCaptureKind.PROPOSED)
            .filter(capture -> !capture.replacedMemoryIds().isEmpty())
            .ifPresent(capture -> {
                capture.replacing(supersede(user, MemoryAccess.owner(), memoryId, capture.replacedMemoryIds()));
                captures.save(capture);
            });
    }


    /**
     * 받아들이려는 제안이 고치기 제안이면 고칠 항목이 제안 때의 판 그대로인지 본다. 그 사이 사람이 고쳤으면 옛 글로 만든 제안이 최신 기억을 대신하지 않게
     * 받아들이기를 막는다. 고칠 항목이 없어졌거나 다른 기억이 대신했으면 대신할 것이 없으므로 막지 않는다.
     *
     * @throws ApiException 고칠 항목이 바뀌었으면 MEMORY_REVISION_CONFLICT
     */
    @Transactional
    public void requireUnchangedTarget(Long memoryId) {
        captures.findFirstByMemoryIdAndKindOrderByIdDesc(memoryId, MemoryCaptureKind.PROPOSED)
            .filter(capture -> capture.baseRevision() != null && capture.replacedMemoryIds().size() == 1)
            .ifPresent(capture -> memories.findByIdForUpdate(capture.replacedMemoryIds().getFirst())
                .filter(target -> target.supersededByMemoryId() == null && target.revision() != capture.baseRevision())
                .ifPresent(target -> {
                    throw new ApiException(ErrorCode.MEMORY_REVISION_CONFLICT, "the memory to update has changed after the proposal");
                }));
    }


    /** 번호들 가운데 요청자가 지금 읽을 수 있고 풀리는 기억의 지금 제목을 번호 순으로 낸다. */
    public Map<Long, String> titlesOf(CurrentUser user, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return memories.findAllById(ids).stream()
            .filter(memory -> memory.isReadableBy(user.id(), user.groupId()) && memory.readable())
            .collect(Collectors.toMap(Memory::id, Memory::title, (left, right) -> left, TreeMap::new));
    }


    private static boolean replaceable(CurrentUser user, MemoryAccess access, Memory memory, Long newId) {
        return memory.scope() == MemoryScope.USER
            && Objects.equals(memory.ownerUserId(), user.id())
            && memory.entryType() == MemoryEntryType.MEMORY
            && memory.status() == MemoryStatus.ACCEPTED
            && memory.supersededByMemoryId() == null
            && memory.readable()
            && access.allows(memory.collection())
            && !memory.id().equals(newId);
    }
}
