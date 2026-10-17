package com.bifos.assistant.memory.application;

import com.bifos.assistant.agent.application.AgentMemoryCollectionService;
import com.bifos.assistant.agent.application.AgentMemoryGrants;
import com.bifos.assistant.feedback.application.DecisionFeedbackRecorder;
import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.memory.application.model.MemoryAccess;
import com.bifos.assistant.memory.application.model.MemorySearchCursor;
import com.bifos.assistant.memory.application.model.MemorySearchPage;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryCollection;
import com.bifos.assistant.memory.domain.MemoryPlacement;
import com.bifos.assistant.memory.domain.entity.MemoryRevision;
import com.bifos.assistant.memory.domain.StoredContent;
import com.bifos.assistant.memory.domain.type.MemoryEntryType;
import com.bifos.assistant.memory.domain.type.MemoryLinkStatus;
import com.bifos.assistant.memory.domain.type.MemoryLinkType;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
import com.bifos.assistant.memory.domain.type.MemoryStatus;
import com.bifos.assistant.memory.infra.MemoryLinkRepository;
import com.bifos.assistant.memory.infra.MemoryQueries;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.infra.MemoryRevisionRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Memory 를 읽고 쓴다.
 *
 * <p>에이전트의 실행이 받는 항목은 세 조건을 모두 지난 것이다. 범위(USER 의 주인, GROUP 의 같은 그룹), 그 에이전트가
 * 받는 collection, 그 collection 에서 허용받은 민감도다(ADR-053). 사람이 화면에서 보는 목록은 범위만 본다.
 *
 * <p>민감 항목의 본문은 암호문으로 저장한다(ADR-055).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemoryService {

    private static final Sort BY_ID = Sort.by("id");

    /** 문서 이름은 소문자나 숫자로 시작하고 소문자, 숫자, 하이픈만 쓴다. 128자까지다(ADR-057). */
    private static final Pattern DOCUMENT_KEY = Pattern.compile("[a-z0-9][a-z0-9-]{0,127}");

    private final MemoryRepository memories;
    private final MemoryRevisionRepository revisions;
    private final MemoryLinkRepository links;
    private final MemoryLinkService linkService;
    private final AgentMemoryCollectionService agentCollections;
    private final MemoryCollectionService collections;
    private final MemoryContentCipher cipher;
    private final MemoryRevisionWriter revisionWriter;
    private final MemoryRelevanceSearch relevanceSearch;
    private final DecisionFeedbackRecorder feedback;
    private final Clock clock;


    /**
     * 이 사용자가 볼 수 있는 Memory 만 낸다. 상태로 거르지 않는다. 화면이 제안도 봐야 한다.
     *
     * <p>문서는 문서 API 가 따로 낸다(ADR-057).
     */
    public List<Memory> readableBy(CurrentUser user) {
        return memories.findAll(MemoryQueries.listedFor(user.id(), user.groupId()), BY_ID);
    }


    /** 요청자가 주인인 USER 범위의 문서를 collection 과 이름 순으로 낸다. 본문은 {@link #contentOf} 로 따로 읽는다. */
    public List<Memory> documentsOf(CurrentUser user) {
        return memories.findByScopeAndOwnerUserIdAndEntryTypeOrderByCollectionAscDocumentKeyAsc(
            MemoryScope.USER, user.id(), MemoryEntryType.DOCUMENT);
    }


    /** 요청자가 주인인 USER 범위의 제안을 만든 순으로 낸다. 먼저 알리기의 판정이 읽는다. */
    public List<Memory> proposalsOf(CurrentUser user) {
        return memories.findByScopeAndOwnerUserIdAndStatusOrderByIdAsc(
            MemoryScope.USER, user.id(), MemoryStatus.PROPOSED);
    }


    /** 번호로 문서 하나를 읽는다. 없는 문서와 남의 문서와 문서가 아닌 항목은 같은 MEMORY_NOT_FOUND 다. */
    public Memory documentFor(CurrentUser user, Long id) {
        return requireOwnDocument(user, memories.findById(id).orElseThrow(MemoryService::notFound));
    }


    /**
     * 요청자의 그룹이 쓰는 collection 이다. 그룹이 없으면 빈 목록이다.
     *
     * <p>줄이 없는 그룹이면 기본 목록을 저장하므로 읽기 전용 트랜잭션으로 두지 않는다.
     */
    @Transactional
    public List<MemoryCollection> collectionsFor(CurrentUser user) {
        return user.groupId() == null ? List.of() : collections.collectionsOf(user.groupId());
    }


    /**
     * 문서를 만든다. 곧 ACCEPTED 이고 꺼내는 방식은 SEARCH 다(ADR-057).
     *
     * @throws ApiException 이름이나 collection 이 틀리면 VALIDATION_FAILED, 같은 이름이 있으면 MEMORY_DOCUMENT_EXISTS,
     * 민감 문서인데 key 가 없으면 MEMORY_ENCRYPTION_UNAVAILABLE
     */
    @Transactional
    public Memory createDocument(
        CurrentUser user,
        String collection,
        String documentKey,
        String title,
        String content,
        MemorySensitivity sensitivity) {
        if (documentKey == null || !DOCUMENT_KEY.matcher(documentKey).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "a document key is invalid");
        }
        if (collectionsFor(user).stream().noneMatch(candidate -> candidate.key().equals(collection))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "a collection is not available");
        }
        if (memories.findByScopeAndOwnerUserIdAndCollectionAndDocumentKey(
            MemoryScope.USER, user.id(), collection, documentKey)
            .isPresent()) {
            throw documentExists();
        }
        // 암호화는 저장 전에 한다. key 가 없으면 여기서 거절돼 평문이 저장되지 않는다
        StoredContent body = stored(content, sensitivity, "USER:" + user.id());
        try {
            return memories.saveAndFlush(
                Memory.document(user.id(), collection, documentKey, title, body, sensitivity, clock.instant()));
        } catch (DataIntegrityViolationException e) {
            // 두 요청이 중복 조회를 함께 지난 경우다. 유일 제약이 막는다
            throw documentExists();
        }
    }


    /**
     * 문서를 고친다. 화면이 읽은 판이 지금 판이 아니면 거절한다(ADR-057).
     *
     * @throws ApiException 판이 다르면 MEMORY_REVISION_CONFLICT
     */
    @Transactional
    public Memory reviseDocument(
        CurrentUser user, Long id, String content, MemorySensitivity sensitivity, int expectedRevision) {
        Memory memory = requireOwnDocument(user, memories.findByIdForUpdate(id).orElseThrow(MemoryService::notFound));
        if (memory.revision() != expectedRevision) {
            throw new ApiException(ErrorCode.MEMORY_REVISION_CONFLICT, "the document revision has changed");
        }
        return revise(user, memory, content, memory.retrieval(), sensitivity);
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


    /** 제목과 일반 본문에서 관련도 순으로 찾는다. 외부 트랜잭션과 분리해 검색 statement에만 2초 제한을 적용한다. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, timeout = 2)
    public MemorySearchPage searchFor(CurrentUser user, MemoryAccess access, String query, int limit, MemorySearchCursor cursor, Optional<float[]> queryVector) {
        return relevanceSearch.searchFor(user, access, query, limit, cursor, queryVector);
    }


    /**
     * 제목만 실렸던 ACCEPTED 항목의 본문을 낸다.
     *
     * <p>볼 수 없는 항목, 승인 전인 항목, 색인에 실리지 않는 항목, 이 실행이 받지 않는 collection 의 항목, 허용받지 않은
     * 민감 항목은 모두 없는 항목과 같은 MEMORY_NOT_FOUND 다. 다르게 답하면 그 항목이 있다는 사실이 새어 나간다.
     */
    public Memory bodyFor(CurrentUser user, MemoryAccess access, Long id) {
        Memory memory = requireReadable(user, id);
        if (!readableByTool(memory, access)) {
            throw notFound();
        }
        return memory;
    }


    /**
     * 한 항목이 그 접근 범위의 {@code memory_read} 로 읽히는 항목인가. {@link #bodyFor} 가 같은 판정을 쓴다. 대체된 항목은 읽히지 않는다(ADR-20261015
     * / memory-link).
     */
    public boolean readableByTool(Memory memory, MemoryAccess access) {
        return memory.status() == MemoryStatus.ACCEPTED
            && memory.retrieval() == MemoryRetrieval.SEARCH
            && memory.entryType() != MemoryEntryType.SOURCE
            && access.allows(memory.collection(), memory.sensitivity())
            && !links.existsByToMemoryIdAndTypeAndStatus(memory.id(), MemoryLinkType.SUPERSEDES, MemoryLinkStatus.ACTIVE);
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
            StoredContent body = stored(content, sensitivity, "GROUP:" + user.groupId());
            return memories.save(
                Memory.accepted(scope, null, user.groupId(), title, body, placement, user.id(), clock.instant()));
        }
        StoredContent body = stored(content, sensitivity, "USER:" + user.id());
        return memories.save(
            Memory.accepted(scope, user.id(), null, title, body, placement, user.id(), clock.instant()));
    }


    /** 에이전트가 제안한 개인 항목을 만든다. GROUP 제안은 만들지 않는다. */
    @Transactional
    public Memory proposeUser(CurrentUser user, String title, String content, Long proposedByExecutionId) {
        String dedupKey = proposalDedupKey(user.id(), title, content);
        return memories.findByProposalDedupKey(dedupKey).orElseGet(() -> {
            Instant now = clock.instant();
            Memory proposed = memories.save(Memory.proposedUser(user.id(), title, content, proposedByExecutionId, dedupKey, now));
            feedback.record(
                MemoryProposalFeedback.of(user, proposed, FeedbackEventType.SURFACED, FeedbackActor.AGENT, now));
            return proposed;
        });
    }


    @Transactional
    public Memory accept(CurrentUser user, Long id) {
        Memory memory = requireMemoryForUpdate(user, id);
        boolean proposal = memory.status() == MemoryStatus.PROPOSED;
        Instant now = clock.instant();
        memory.accept(user.id(), now);
        Memory accepted = memories.save(memory);
        if (proposal) {
            feedback.record(
                MemoryProposalFeedback.of(user, accepted, FeedbackEventType.ACCEPTED, FeedbackActor.USER, now));
        }
        return accepted;
    }


    @Transactional
    public Memory reject(CurrentUser user, Long id) {
        Memory memory = requireMemoryForUpdate(user, id);
        boolean proposal = memory.status() == MemoryStatus.PROPOSED;
        Instant now = clock.instant();
        memory.reject(now);
        Memory rejected = memories.save(memory);
        // 받아들인 항목을 거절했으면 그 항목의 연결도 끊는다. 실리지 않는 새 항목이 옛 항목을 가리지 않게 한다
        linkService.revokeAllOf(id);
        if (proposal) {
            feedback.record(
                MemoryProposalFeedback.of(user, rejected, FeedbackEventType.REJECTED, FeedbackActor.USER, now));
        }
        return rejected;
    }


    /**
     * 지금 화면의 수정이다. 민감도는 그대로 두고 본문과 항상 실을지만 바꾼다.
     *
     * <p>항상 싣지 않게 바꾸면 색인으로 간다. 이미 보관(ARCHIVE)한 항목은 보관한 채로 둔다. 이 화면이 보관을 모르므로
     * 본문만 고친 것이 그 항목을 색인으로 되살리지 않게 한다.
     *
     * <p>민감 항목은 이 경로로 고치지 못한다. 목록이 본문을 싣지 않아 이 요청이 본문을 읽지 않은 채 덮어쓴다(ADR-055).
     *
     * @throws ApiException 민감 항목일 때. MEMORY_SENSITIVE_NOT_EDITABLE
     */
    @Transactional
    public Memory update(CurrentUser user, Long id, String content, boolean alwaysInject) {
        Memory memory = requireMemoryForUpdate(user, id);
        if (memory.sensitivity() == MemorySensitivity.SENSITIVE) {
            throw new ApiException(
                ErrorCode.MEMORY_SENSITIVE_NOT_EDITABLE, "a sensitive memory cannot be edited from the list");
        }
        MemoryRetrieval retrieval = alwaysInject
            ? MemoryRetrieval.ALWAYS
            : memory.retrieval() == MemoryRetrieval.ARCHIVE ? MemoryRetrieval.ARCHIVE : MemoryRetrieval.SEARCH;
        return revise(user, memory, content, retrieval, memory.sensitivity());
    }


    /** 본문과 꺼내는 방식과 민감도를 고친다. 고치기 전의 값을 판으로 남기고 판 번호를 하나 올린다. */
    @Transactional
    public Memory update(
        CurrentUser user, Long id, String content, MemoryRetrieval retrieval, MemorySensitivity sensitivity) {
        return revise(user, requireMemoryForUpdate(user, id), content, retrieval, sensitivity);
    }


    /** 항목을 지운다. 마지막 값을 판으로 남기고 그 벡터를 지운 뒤 줄을 지운다. */
    @Transactional
    public void delete(CurrentUser user, Long id) {
        Memory memory = requireWritableForUpdate(user, id);
        revisionWriter.beforeDelete(user, memory);
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


    /**
     * 본문을 평문으로 낸다. 본문을 밖으로 내는 자리는 엔티티의 {@code content()} 가 아니라 이것을 쓴다.
     *
     * @throws ApiException 암호화 key 가 없을 때. MEMORY_ENCRYPTION_UNAVAILABLE
     */
    public String contentOf(Memory memory) {
        return memory.sealed()
            ? cipher.open(memory.content(), memory.contentKeyId(), memory.contentBinding())
            : memory.content();
    }


    StoredContent stored(String plain, MemorySensitivity sensitivity, String binding) {
        return revisionWriter.stored(plain, sensitivity, binding);
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


    Memory revise(
        CurrentUser user, Memory memory, String content, MemoryRetrieval retrieval, MemorySensitivity sensitivity) {
        return revisionWriter.revise(user, memory, content, retrieval, sensitivity);
    }


    /**
     * 고치거나 지우거나 승인 상태를 바꿀 항목을 쓰기 잠금으로 읽고 쓸 수 있는지 본다.
     *
     * <p>승인과 거절도 잠근다. 잠그지 않고 읽은 값을 통째로 저장하면 그 사이에 커밋된 수정의 본문과 판 번호를 옛 값으로
     * 되돌린다.
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


    /**
     * 종류가 MEMORY 인 항목만 잠가 읽는다. 문서는 이 경로로 승인하거나 고치지 못한다. 문서는 문서 API 가 고치고, 이
     * 경로는 항상 싣기를 켤 수 있다(ADR-057).
     */
    private Memory requireMemoryForUpdate(CurrentUser user, Long id) {
        Memory memory = requireWritableForUpdate(user, id);
        if (memory.entryType() != MemoryEntryType.MEMORY) {
            throw notFound();
        }
        return memory;
    }


    private static Memory requireOwnDocument(CurrentUser user, Memory memory) {
        if (memory.scope() != MemoryScope.USER
            || !user.id().equals(memory.ownerUserId())
            || memory.entryType() != MemoryEntryType.DOCUMENT) {
            throw notFound();
        }
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
        MemoryRevisionWriter.requirePlaceable(retrieval, sensitivity);
        return new MemoryPlacement(collection, retrieval, sensitivity);
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


    private static ApiException documentExists() {
        return new ApiException(ErrorCode.MEMORY_DOCUMENT_EXISTS, "a document with that name already exists");
    }


    private static ApiException notFound() {
        return new ApiException(ErrorCode.MEMORY_NOT_FOUND, "no such memory");
    }


    static String proposalDedupKey(Long ownerUserId, String title, String content) {
        return MemoryRevisionWriter.proposalDedupKey(ownerUserId, title, content);
    }
}
