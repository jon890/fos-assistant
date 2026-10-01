package com.bifos.assistant.memory.application;

import com.bifos.assistant.agent.application.AgentMemoryCollectionService;
import com.bifos.assistant.agent.application.AgentMemoryGrants;
import com.bifos.assistant.memory.application.model.MemoryAccess;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.MemoryPlacement;
import com.bifos.assistant.memory.domain.MemoryRevision;
import com.bifos.assistant.memory.domain.type.MemoryChangeType;
import com.bifos.assistant.memory.domain.type.MemoryEntryType;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.domain.type.MemoryStatus;
import com.bifos.assistant.memory.infra.MemoryQueries;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.infra.MemoryRevisionRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.shared.util.Sha256;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Memory 를 읽고 쓴다.
 *
 * <p>에이전트의 실행이 받는 항목은 세 조건을 모두 지난 것이다. 범위(USER 의 주인, GROUP 의 같은 그룹), 그 에이전트가
 * 받는 collection, 그 collection 에서 허용받은 민감도다(ADR-052). 사람이 화면에서 보는 목록은 범위만 본다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemoryService {

    private static final Sort BY_ID = Sort.by("id");

    private final MemoryRepository memories;
    private final MemoryRevisionRepository revisions;
    private final AgentMemoryCollectionService agentCollections;
    private final Clock clock;

    /** 이 사용자가 볼 수 있는 항목만 낸다. 상태로 거르지 않는다. 화면이 제안도 봐야 한다. */
    public List<Memory> readableBy(CurrentUser user) {
        return memories.findAll(MemoryQueries.readableBy(user.id(), user.groupId()), BY_ID);
    }

    /**
     * 그 에이전트의 실행이 받는 collection 이다.
     *
     * <p>커넥터 에이전트, 찾지 못한 에이전트, 에이전트가 없는 실행은 아무것도 받지 않는다.
     *
     * @param agentId 실행의 에이전트 번호. 없는 실행이면 null 이다
     */
    public MemoryAccess accessOf(Long agentId) {
        AgentMemoryGrants grants = agentCollections.grantsOf(agentId);
        return MemoryAccess.of(grants.collections(), grants.sensitiveCollections());
    }

    /** 본문까지 항상 싣는 항목. ACCEPTED 이고 꺼내는 방식이 ALWAYS 인 것만이다. */
    public List<Memory> alwaysInjectedFor(CurrentUser user, MemoryAccess access) {
        return injectableFor(user, access, MemoryRetrieval.ALWAYS);
    }

    /** 제목만 싣는 항목. ACCEPTED 이고 꺼내는 방식이 SEARCH 인 것이다. ARCHIVE 와 출처 원문은 싣지 않는다. */
    public List<Memory> indexedFor(CurrentUser user, MemoryAccess access) {
        return injectableFor(user, access, MemoryRetrieval.SEARCH);
    }

    /**
     * 제목만 실렸던 ACCEPTED 항목의 본문을 낸다.
     *
     * <p>볼 수 없는 항목, 승인 전인 항목, 색인에 실리지 않는 항목, 이 실행이 받지 않는 collection 의 항목, 허용받지 않은
     * 민감 항목은 모두 없는 항목과 같은 MEMORY_NOT_FOUND 다. 다르게 답하면 그 항목이 있다는 사실이 새어 나간다.
     */
    public Memory bodyFor(CurrentUser user, MemoryAccess access, Long id) {
        Memory memory = requireReadable(user, id);
        if (memory.status() != MemoryStatus.ACCEPTED
                || memory.retrieval() != MemoryRetrieval.SEARCH
                || memory.entryType() == MemoryEntryType.SOURCE
                || !access.allows(memory.collection(), memory.sensitivity())) {
            throw notFound();
        }
        return memory;
    }

    /** 지금 화면이 만드는 항목이다. core collection 에 민감하지 않게 둔다. */
    @Transactional
    public Memory create(CurrentUser user, MemoryScope scope, String title, String content, boolean alwaysInject) {
        return create(
                user,
                scope,
                title,
                content,
                Memory.DEFAULT_COLLECTION,
                MemoryRetrieval.ofAlwaysInject(alwaysInject),
                MemorySensitivity.NORMAL);
    }

    @Transactional
    public Memory create(
            CurrentUser user,
            MemoryScope scope,
            String title,
            String content,
            String collection,
            MemoryRetrieval retrieval,
            MemorySensitivity sensitivity) {
        if (scope == null) {
            throw new ApiException(ErrorCode.MEMORY_SCOPE_REQUIRED, "memory scope is required");
        }
        MemoryPlacement placement = placement(collection, retrieval, sensitivity);
        if (scope == MemoryScope.GROUP) {
            requireAdmin(user);
            return memories.save(Memory.accepted(
                    scope, null, user.groupId(), title, content, placement, user.id(), clock.instant()));
        }
        return memories.save(
                Memory.accepted(scope, user.id(), null, title, content, placement, user.id(), clock.instant()));
    }

    /** 에이전트가 제안한 개인 항목을 만든다. GROUP 제안은 만들지 않는다. */
    @Transactional
    public Memory proposeUser(CurrentUser user, String title, String content, Long proposedByExecutionId) {
        String dedupKey = proposalDedupKey(user.id(), title, content);
        return memories.findByProposalDedupKey(dedupKey)
                .orElseGet(() -> memories.save(Memory.proposedUser(
                        user.id(), title, content, proposedByExecutionId, dedupKey, clock.instant())));
    }

    @Transactional
    public Memory accept(CurrentUser user, Long id) {
        Memory memory = requireReadable(user, id);
        requireWritable(user, memory);
        memory.accept(user.id(), clock.instant());
        return memories.save(memory);
    }

    @Transactional
    public Memory reject(CurrentUser user, Long id) {
        Memory memory = requireReadable(user, id);
        requireWritable(user, memory);
        memory.reject(clock.instant());
        return memories.save(memory);
    }

    /**
     * 지금 화면의 수정이다. 민감도는 그대로 두고 본문과 항상 실을지만 바꾼다.
     *
     * <p>항상 싣지 않게 바꾸면 색인으로 간다. 이미 보관(ARCHIVE)한 항목은 보관한 채로 둔다. 이 화면이 보관을 모르므로
     * 본문만 고친 것이 그 항목을 색인으로 되살리지 않게 한다.
     */
    @Transactional
    public Memory update(CurrentUser user, Long id, String content, boolean alwaysInject) {
        Memory memory = requireWritableForUpdate(user, id);
        MemoryRetrieval retrieval = alwaysInject
                ? MemoryRetrieval.ALWAYS
                : memory.retrieval() == MemoryRetrieval.ARCHIVE ? MemoryRetrieval.ARCHIVE : MemoryRetrieval.SEARCH;
        return revise(user, memory, content, retrieval, memory.sensitivity());
    }

    /** 본문과 꺼내는 방식과 민감도를 고친다. 고치기 전의 값을 판으로 남기고 판 번호를 하나 올린다. */
    @Transactional
    public Memory update(
            CurrentUser user, Long id, String content, MemoryRetrieval retrieval, MemorySensitivity sensitivity) {
        return revise(user, requireWritableForUpdate(user, id), content, retrieval, sensitivity);
    }

    /** 항목을 지운다. 마지막 값을 판으로 남긴 뒤 줄을 지운다. */
    @Transactional
    public void delete(CurrentUser user, Long id) {
        Memory memory = requireWritableForUpdate(user, id);
        revisions.save(MemoryRevision.of(memory, MemoryChangeType.DELETED, user.id(), null, clock.instant()));
        memories.delete(memory);
    }

    /**
     * 한 항목의 물러난 판을 오래된 것부터 낸다. 지운 항목도 그 번호로 찾는다.
     *
     * <p>판이 적어 둔 범위와 주인으로 볼 수 있는 것만 낸다. 남의 항목과 없는 항목은 똑같이 빈 목록이다.
     */
    public List<MemoryRevision> revisionsOf(CurrentUser user, Long id) {
        return revisions.findByIdMemoryIdOrderByIdRevisionAsc(id).stream()
                .filter(revision -> revision.scope() == MemoryScope.USER
                        ? user.id().equals(revision.ownerUserId())
                        : user.groupId() != null && user.groupId().equals(revision.groupId()))
                .toList();
    }

    private List<Memory> injectableFor(CurrentUser user, MemoryAccess access, MemoryRetrieval retrieval) {
        if (access.isEmpty()) {
            return List.of();
        }
        return memories.findAll(
                MemoryQueries.injectable(
                        user.id(),
                        user.groupId(),
                        retrieval,
                        access.unrestricted() ? null : access.collections(),
                        access.sensitiveCollections()),
                BY_ID);
    }

    private Memory revise(
            CurrentUser user, Memory memory, String content, MemoryRetrieval retrieval, MemorySensitivity sensitivity) {
        requirePlaceable(retrieval, sensitivity);
        String dedupKey = memory.proposalDedupKey() == null
                ? null
                : proposalDedupKey(memory.ownerUserId(), memory.title(), content);
        revisions.save(MemoryRevision.of(memory, MemoryChangeType.UPDATED, user.id(), null, clock.instant()));
        memory.revise(content, retrieval, sensitivity, dedupKey, clock.instant());
        return memories.save(memory);
    }

    /**
     * 고치거나 지울 항목을 쓰기 잠금으로 읽고 쓸 수 있는지 본다.
     *
     * <p>잠근 뒤에 읽은 값으로 판을 남겨야 하므로, 이 트랜잭션에서 그 항목을 먼저 읽어 두지 않는다.
     */
    private Memory requireWritableForUpdate(CurrentUser user, Long id) {
        Memory memory = memories.findByIdForUpdate(id).orElseThrow(MemoryService::notFound);
        if (!memory.isReadableBy(user.id(), user.groupId())) {
            throw notFound();
        }
        requireWritable(user, memory);
        return memory;
    }

    private Memory requireReadable(CurrentUser user, Long id) {
        Memory memory = memories.findById(id).orElseThrow(MemoryService::notFound);
        if (!memory.isReadableBy(user.id(), user.groupId())) {
            throw notFound();
        }
        return memory;
    }

    private static MemoryPlacement placement(
            String collection, MemoryRetrieval retrieval, MemorySensitivity sensitivity) {
        if (!MemoryPlacement.isCollectionKey(collection)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "a collection key is invalid");
        }
        requirePlaceable(retrieval, sensitivity);
        return new MemoryPlacement(collection, retrieval, sensitivity);
    }

    private static void requirePlaceable(MemoryRetrieval retrieval, MemorySensitivity sensitivity) {
        if (retrieval == null || sensitivity == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "retrieval and sensitivity are required");
        }
        if (!MemoryPlacement.allows(retrieval, sensitivity)) {
            throw new ApiException(ErrorCode.MEMORY_SENSITIVE_ALWAYS, "a sensitive memory cannot always be injected");
        }
    }

    private static void requireAdmin(CurrentUser user) {
        if (!user.isAdmin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "this action is limited to the group admin");
        }
    }

    private static void requireWritable(CurrentUser user, Memory memory) {
        if (memory.scope() == MemoryScope.GROUP && !user.isAdmin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "this action is limited to the group admin");
        }
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.MEMORY_NOT_FOUND, "no such memory");
    }

    private static String proposalDedupKey(Long ownerUserId, String title, String content) {
        return Sha256.hex(ownerUserId + "\u0000" + title + "\u0000" + content);
    }
}
