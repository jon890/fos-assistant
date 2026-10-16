package com.bifos.assistant.memory.application;

import com.bifos.assistant.memory.application.model.MemoryAccess;
import com.bifos.assistant.memory.application.model.SupersedeProposal;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryLink;
import com.bifos.assistant.memory.domain.type.MemoryEntryType;
import com.bifos.assistant.memory.domain.type.MemoryLinkOrigin;
import com.bifos.assistant.memory.domain.type.MemoryLinkStatus;
import com.bifos.assistant.memory.domain.type.MemoryLinkType;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemoryStatus;
import com.bifos.assistant.memory.infra.MemoryLinkRepository;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기억 두 개 사이의 연결을 만들고, 받아들이고, 끊는다(ADR-20261015 / memory-link).
 *
 * <p>양 끝은 요청자가 볼 수 있는 {@code ACCEPTED} 항목이고 같은 범위와 주인이어야 한다. 대체는 늘 제안으로 만들고 옛 항목을 고칠 수 있는 사람이
 * 받아들인다. 이 서비스는 항목의 본문과 제목을 고치지 않는다. 예외 메시지와 로그에는 번호만 남긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemoryLinkService {

    /** {@code RELATED} 의 자유 글 상한이다. 컬럼 길이와 같다. */
    public static final int LABEL_MAX = 200;

    private final MemoryLinkRepository links;
    private final MemoryRepository memories;
    private final Clock clock;


    /**
     * 새 항목이 옛 항목을 대신한다는 제안을 남긴다. 같은 쌍의 줄이 이미 제안이거나 효력이 있으면 그 줄을 그대로 돌려주고, 끊은 줄이면 다시 제안으로
     * 연다.
     *
     * @throws ApiException 볼 수 없거나 규칙을 어긴 항목은 MEMORY_NOT_FOUND
     */
    @Transactional
    public MemoryLink proposeSupersede(
        CurrentUser user, Long newId, Long oldId, MemoryLinkOrigin origin, Long executionId) {
        requireLinkable(user, newId, oldId);
        return upsert(
            newId,
            oldId,
            MemoryLinkType.SUPERSEDES,
            () -> MemoryLink.proposeSupersede(newId, oldId, origin, executionId),
            link -> link.reopen(null, origin, null, executionId));
    }


    /**
     * 두 항목을 관련 연결로 잇는다. 바로 효력이 있다.
     *
     * @throws ApiException 볼 수 없거나 규칙을 어긴 항목은 MEMORY_NOT_FOUND, label 이 200자를 넘으면 VALIDATION_FAILED
     */
    @Transactional
    public MemoryLink relate(
        CurrentUser user, Long a, Long b, String label, MemoryLinkOrigin origin, Double score, Long executionId) {
        if (label != null && label.length() > LABEL_MAX) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "a link label is too long");
        }
        requireLinkable(user, a, b);
        String trimmed = label == null || label.isBlank() ? null : label.strip();
        return upsert(
            Math.min(a, b),
            Math.max(a, b),
            MemoryLinkType.RELATED,
            () -> MemoryLink.related(a, b, trimmed, origin, score, executionId),
            link -> link.reopen(trimmed, origin, score, executionId));
    }


    /**
     * 대체 제안을 받아들인다. 옛 항목이 이미 다른 줄로 대체됐어도 두 줄을 함께 둔다. 「대체됨」 은 하나라도 있으면 참이다.
     *
     * <p>새 항목이 이미 대체됐으면 받아들이지 않는다. 서로를 대체하는 두 제안을 모두 받아들이면 두 항목이 함께 주입에서 사라진다.
     *
     * @throws ApiException 없는 줄과 볼 수 없는 줄은 MEMORY_NOT_FOUND, 그룹 항목을 {@code MEMBER} 가 받아들이면 FORBIDDEN, 제안이
     * 아니거나 양 끝이 더는 연결할 수 없는 항목이면 MEMORY_LINK_STATE_CONFLICT
     */
    @Transactional
    public MemoryLink accept(CurrentUser user, Long linkId) {
        MemoryLink link = requireDecidable(user, linkId);
        if (link.type() != MemoryLinkType.SUPERSEDES || link.status() != MemoryLinkStatus.PROPOSED) {
            throw stateConflict();
        }
        // 제안을 남긴 뒤 새 항목이 지워지거나 범위가 달라졌을 수 있다. 받아들이는 트랜잭션 안에서 다시 본다
        Memory from = memories.findById(link.fromMemoryId()).orElse(null);
        Memory to = memories.findById(link.toMemoryId()).orElse(null);
        if (from == null || to == null || !linkable(user, from, to) || isSuperseded(from.id())) {
            throw stateConflict();
        }
        link.accept();
        log.info("memory link accepted linkId={} userId={}", link.id(), user.id());
        return links.save(link);
    }


    /**
     * 대체 제안을 거절한다. 줄은 {@code REVOKED} 로 남는다.
     *
     * @throws ApiException 제안이 아니면 MEMORY_LINK_STATE_CONFLICT. 나머지는 {@link #accept} 와 같다
     */
    @Transactional
    public MemoryLink reject(CurrentUser user, Long linkId) {
        MemoryLink link = requireDecidable(user, linkId);
        if (link.status() != MemoryLinkStatus.PROPOSED) {
            throw stateConflict();
        }
        link.revoke(clock.instant());
        log.info("memory link rejected linkId={} userId={}", link.id(), user.id());
        return links.save(link);
    }


    /**
     * 효력이 있는 연결을 끊는다. 대체를 되돌리면 옛 항목이 다음 조립부터 다시 실린다.
     *
     * @throws ApiException 효력이 있는 줄이 아니면 MEMORY_LINK_STATE_CONFLICT. 나머지는 {@link #accept} 와 같다
     */
    @Transactional
    public MemoryLink revoke(CurrentUser user, Long linkId) {
        MemoryLink link = requireDecidable(user, linkId);
        if (link.status() != MemoryLinkStatus.ACTIVE) {
            throw stateConflict();
        }
        link.revoke(clock.instant());
        log.info("memory link revoked linkId={} userId={}", link.id(), user.id());
        return links.save(link);
    }


    /** 그 번호 가운데 대체된 항목마다 그 항목을 대신한 효력 있는 줄이다. 줄이 여럿이면 가장 나중에 맺은 줄이다. */
    public Map<Long, MemoryLink> supersededBy(Collection<Long> memoryIds) {
        if (memoryIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, MemoryLink> result = new LinkedHashMap<>();
        links.findByToMemoryIdInAndTypeAndStatus(memoryIds, MemoryLinkType.SUPERSEDES, MemoryLinkStatus.ACTIVE)
            .stream()
            .sorted(Comparator.comparing(MemoryLink::id))
            .forEach(link -> result.put(link.toMemoryId(), link));
        return result;
    }


    /** 한 항목이 지금 대체됐는가. 효력 있는 대체 줄이 하나라도 그 항목을 가리키면 참이다. */
    public boolean isSuperseded(Long memoryId) {
        return links.existsByToMemoryIdAndTypeAndStatus(memoryId, MemoryLinkType.SUPERSEDES, MemoryLinkStatus.ACTIVE);
    }


    /**
     * 요청자가 받아들이거나 거절할 수 있는 대체 제안을 양 끝 항목과 함께 낸다. 개인 항목은 주인, 그룹 항목은 그룹의 {@code ADMIN} 이 받는다.
     * 새 항목이 그 사이에 볼 수 없게 됐거나 대체된 제안은 받아들일 수 없으므로 뺀다.
     */
    public List<SupersedeProposal> pendingSupersedesFor(CurrentUser user) {
        List<MemoryLink> pending = new ArrayList<>(links.findProposedSupersedesOfOwner(user.id()));
        if (user.isAdmin() && user.groupId() != null) {
            pending.addAll(links.findProposedSupersedesOfGroup(user.groupId()));
        }
        if (pending.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = new HashSet<>();
        pending.forEach(link -> {
            ids.add(link.fromMemoryId());
            ids.add(link.toMemoryId());
        });
        Map<Long, Memory> byId = memories.findAllById(ids).stream()
            .collect(Collectors.toMap(Memory::id, Function.identity()));
        Map<Long, MemoryLink> superseded = supersededBy(ids);
        return pending.stream()
            .sorted(Comparator.comparing(MemoryLink::id))
            .filter(link -> byId.containsKey(link.fromMemoryId()) && byId.containsKey(link.toMemoryId()))
            .filter(link -> linkable(user, byId.get(link.fromMemoryId()), byId.get(link.toMemoryId())))
            .filter(link -> !superseded.containsKey(link.fromMemoryId()))
            .map(link -> new SupersedeProposal(link, byId.get(link.fromMemoryId()), byId.get(link.toMemoryId())))
            .toList();
    }


    /**
     * 한 항목이 그 에이전트의 실행이 연결할 수 있는 항목인가. 요청자가 볼 수 있는 {@code ACCEPTED} 항목이고, 출처 원문도 보관한 항목도 아니고,
     * 실행이 받는 collection 과 민감도 안이며, 대체되지 않았어야 한다. 에이전트는 지시문과 검색에서 본 항목만 잇는다.
     */
    public boolean visibleToAgent(CurrentUser user, MemoryAccess access, Long memoryId) {
        Memory memory = memories.findById(memoryId).orElse(null);
        return memory != null
            && endpoint(user, memory)
            && memory.retrieval() != MemoryRetrieval.ARCHIVE
            && access.allows(memory.collection(), memory.sensitivity())
            && !isSuperseded(memoryId);
    }


    /** 한 실행이 지금까지 맺은 연결 수다. 도구의 실행당 상한이 쓴다. */
    public long countByExecution(Long executionId) {
        return links.countByCreatedByExecutionId(executionId);
    }


    private MemoryLink upsert(
        Long from,
        Long to,
        MemoryLinkType type,
        Supplier<MemoryLink> create,
        Consumer<MemoryLink> reopen) {
        MemoryLink existing = links.findByFromMemoryIdAndToMemoryIdAndType(from, to, type).orElse(null);
        if (existing == null) {
            MemoryLink created = links.save(create.get());
            log.info("memory link created linkId={} type={} origin={}", created.id(), type, created.origin());
            return created;
        }
        if (!existing.revoked()) {
            return existing;
        }
        reopen.accept(existing);
        log.info("memory link reopened linkId={} type={} origin={}", existing.id(), type, existing.origin());
        return links.save(existing);
    }


    /** 두 번호가 연결할 수 있는 항목인지 본다. 어긴 까닭을 가리지 않고 같은 404 를 낸다. 다르게 답하면 볼 수 없는 항목이 있다는 것이 새어 나간다. */
    private void requireLinkable(CurrentUser user, Long a, Long b) {
        if (a == null || b == null || a.equals(b)) {
            throw notFound();
        }
        Memory first = memories.findById(a).orElseThrow(MemoryLinkService::notFound);
        Memory second = memories.findById(b).orElseThrow(MemoryLinkService::notFound);
        if (!linkable(user, first, second)) {
            throw notFound();
        }
    }


    private static boolean linkable(CurrentUser user, Memory a, Memory b) {
        return endpoint(user, a)
            && endpoint(user, b)
            && a.scope() == b.scope()
            && (a.scope() == MemoryScope.USER
                ? Objects.equals(a.ownerUserId(), b.ownerUserId())
                : Objects.equals(a.groupId(), b.groupId()));
    }


    private static boolean endpoint(CurrentUser user, Memory memory) {
        return memory.isReadableBy(user.id(), user.groupId())
            && memory.status() == MemoryStatus.ACCEPTED
            && memory.entryType() != MemoryEntryType.SOURCE;
    }


    /**
     * 받아들이기와 거절, 되돌리기를 할 수 있는 줄인지 본다. 옛 항목({@code to})을 고칠 수 있는 사람만 한다.
     *
     * <p>줄은 쓰기 잠금으로 읽는다. 함께 온 두 결정 가운데 나중 것은 바뀐 상태를 보고 409 를 받는다.
     *
     * <p>없는 줄과 볼 수 없는 줄은 같은 404 다. 볼 수 있지만 그룹의 {@code ADMIN} 이 아니면 기억 수정과 같은 FORBIDDEN 이다.
     */
    private MemoryLink requireDecidable(CurrentUser user, Long linkId) {
        MemoryLink link = links.findByIdForUpdate(linkId).orElseThrow(MemoryLinkService::notFound);
        Memory to = memories.findById(link.toMemoryId()).orElseThrow(MemoryLinkService::notFound);
        if (!to.isReadableBy(user.id(), user.groupId())) {
            throw notFound();
        }
        if (to.scope() == MemoryScope.GROUP && !user.isAdmin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "this action is limited to the group admin");
        }
        return link;
    }


    private static ApiException stateConflict() {
        return new ApiException(ErrorCode.MEMORY_LINK_STATE_CONFLICT, "the memory link cannot change from its state");
    }


    private static ApiException notFound() {
        return new ApiException(ErrorCode.MEMORY_NOT_FOUND, "no such memory link");
    }
}
