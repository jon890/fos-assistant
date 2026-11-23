package com.bifos.assistant.memory.application;

import com.bifos.assistant.feedback.application.DecisionFeedbackRecorder;
import com.bifos.assistant.feedback.application.FeedbackLabeler;
import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.memory.application.dto.CapturedMemory;
import com.bifos.assistant.memory.application.dto.MemoryAccess;
import com.bifos.assistant.memory.application.dto.MemoryRememberOutcome;
import com.bifos.assistant.memory.application.dto.MemoryRememberRequest;
import com.bifos.assistant.memory.application.dto.MemoryRememberResult;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.memory.domain.entity.MemoryCapture;
import com.bifos.assistant.memory.domain.MemoryPlacement;
import com.bifos.assistant.memory.domain.entity.MemoryRevision;
import com.bifos.assistant.memory.domain.entity.MemoryRevisionId;
import com.bifos.assistant.memory.domain.type.MemoryCaptureKind;
import com.bifos.assistant.memory.domain.type.MemoryEntryType;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemoryStatus;
import com.bifos.assistant.memory.infra.MemoryCaptureRepository;
import com.bifos.assistant.memory.infra.MemoryRepository;
import com.bifos.assistant.memory.infra.MemoryRevisionRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 에이전트가 {@code memory_remember} 로 남기는 기록을 저장하고, 대화에 그릴 기록을 내고, 사람이 되돌린다(ADR-20261007 / memory-remember).
 *
 * <p>바로 저장할지는 부르는 쪽이 실행의 출처로 판정해 {@link MemoryRememberRequest#direct()} 로 넘긴다. 이 클래스는 그 위에
 * 민감도와 collection 과 한 실행의 상한을 다시 본다. 민감 항목은 바로 저장 조건이어도 제안이고,
 * 모델이 일반으로 주어도 민감해 보이는 글이면 제안이다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemoryCaptureService {

    /** 한 실행이 남길 수 있는 기록의 수다. 바로 저장과 제안을 함께 센다. */
    public static final int PER_EXECUTION_MAX = 3;

    private final MemoryService memoryService;
    private final MemoryTexts texts;
    private final MemoryRepository memories;
    private final MemoryRevisionRepository revisions;
    private final MemoryCaptureRepository captures;
    private final MemoryReplacements replacements;
    private final DecisionFeedbackRecorder feedback;
    private final Clock clock;


    /**
     * 사실 하나를 바로 저장하거나 제안으로 남긴다.
     *
     * <p>범위는 늘 {@code USER} 이고 주인은 요청자다. 꺼내는 방식은 {@code SEARCH} 다. 값 검사는 부르는 쪽이 마쳤다.
     */
    @Transactional
    public MemoryRememberResult remember(CurrentUser user, MemoryAccess access, MemoryRememberRequest request) {
        if (captures.countByExecutionId(request.executionId()) >= PER_EXECUTION_MAX) {
            return MemoryRememberResult.of(MemoryRememberOutcome.TOO_MANY);
        }
        if (request.memoryId() != null) {
            return update(user, access, request);
        }
        String collection = request.collection() == null ? Memory.DEFAULT_COLLECTION : request.collection();
        if (!access.collections().contains(collection)) {
            return MemoryRememberResult.of(MemoryRememberOutcome.COLLECTION_NOT_ALLOWED);
        }
        return create(user, access, request, new MemoryPlacement(collection, MemoryRetrieval.SEARCH), request.archivable());
    }


    /**
     * 새 항목을 바로 저장하거나 제안으로 남긴다. 값 검사와 collection 판정은 부르는 쪽이 마쳤다.
     *
     * @param archivable 새 항목에 적을 자동 보관 허락 표시
     */
    private MemoryRememberResult create(
        CurrentUser user, MemoryAccess access, MemoryRememberRequest request, MemoryPlacement placement, boolean archivable) {
        boolean direct = request.direct()
            && !request.sensitive()
            && !MemorySensitiveHints.suspected(request.title(), request.content(), request.summary());
        String dedupKey;
        try {
            dedupKey = texts.proposalDedupKey(user.id(), request.title(), request.content());
        } catch (ApiException ex) {
            if (ex.code() == ErrorCode.MEMORY_ENCRYPTION_UNAVAILABLE) {
                return MemoryRememberResult.of(MemoryRememberOutcome.ENCRYPTION_UNAVAILABLE);
            }
            throw ex;
        }
        // 본문을 먼저 읽으면 뒤에 잠가도 관리 중인 옛 값이 남는다. 번호만 찾고 처음 읽을 때 잠근다.
        Optional<Memory> existing = memories.findIdByProposalDedupKey(dedupKey).flatMap(memories::findByIdForUpdate)
            .filter(memory -> dedupKey.equals(memory.proposalDedupKey()));
        if (existing.isPresent()) {
            return existingOutcome(user, existing.get(), direct, request);
        }
        String body = request.content();
        // 제안에는 요약을 두지 않는다. 제안 카드가 요약을 보이기 전(PR2)에는 사용자가 보지 못한 요약이 받아들이는 순간 매 실행에 들어가기 때문이다
        String summary = !direct || request.summary() == null ? "" : request.summary();
        Memory created = (direct
            ? Memory.remembered(user.id(), request.title(), body, placement, request.executionId(), dedupKey, clock.instant())
            : Memory.proposed(user.id(), request.title(), body, placement, request.executionId(), dedupKey, clock.instant()))
                .fillSummary(summary, null);
        // 자동 보관 허락은 새 항목에만 적는다. 고치는 길은 사람이 정한 값을 모델이 뒤집지 못하게 그대로 둔다
        created.allowArchive(archivable);
        Memory saved;
        try {
            // key 를 풀지 못하면 줄을 넣기 전에 거절된다
            saved = texts.insert(created);
        } catch (ApiException ex) {
            if (ex.code() == ErrorCode.MEMORY_ENCRYPTION_UNAVAILABLE) {
                return MemoryRememberResult.of(MemoryRememberOutcome.ENCRYPTION_UNAVAILABLE);
            }
            throw ex;
        }
        MemoryCaptureKind kind = direct ? MemoryCaptureKind.CREATED : MemoryCaptureKind.PROPOSED;
        // 합치기: 바로 저장은 같은 트랜잭션에서 옛 기억을 대신하고, 제안은 받아들일 때 대신할 번호만 기록에 둔다(설계 DECISION5)
        List<Long> replaced = direct
            ? replacements.supersede(user, access, saved.id(), request.replaces())
            : replacements.replaceable(user, access, request.replaces(), saved.id());
        record(saved.id(), user, request, kind, direct ? saved.revision() : null, null, replaced);
        if (!direct) {
            // 바로 저장은 사용자 본인의 말이라 제안이 아니다. 제안만 판단 피드백의 SURFACED 로 남긴다.
            feedback.record(MemoryProposalFeedback.of(
                user, saved, FeedbackEventType.SURFACED, FeedbackActor.AGENT, clock.instant()));
        }
        return new MemoryRememberResult(
            direct ? MemoryRememberOutcome.REMEMBERED : MemoryRememberOutcome.PROPOSED,
            saved.id(),
            replaced,
            notReplaced(request.replaces(), replaced));
    }


    /** 요청한 번호 가운데 대신하지 못한 것을 요청한 순서로 낸다. */
    private static List<Long> notReplaced(List<Long> requested, List<Long> replaced) {
        return requested.stream().distinct().filter(id -> !replaced.contains(id)).toList();
    }


    /**
     * 한 대화에서 요청자가 남긴 기록과 그 항목을 만든 순으로 낸다. 되돌린 기록과 항목이 없어진 기록은 뺀다. 합친 기록에는 대신한 옛 기억 가운데 요청자가 지금
     * 읽을 수 있는 것의 제목을 붙인다.
     *
     * @param conversationId 요청자의 대화임을 부르는 쪽이 확인한 번호
     */
    public List<CapturedMemory> capturesOf(CurrentUser user, Long conversationId) {
        List<MemoryCapture> rows = captures.findByUserIdAndConversationIdAndUndoneAtIsNullOrderByIdAsc(user.id(), conversationId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, Memory> byId = memories
            .findAllById(rows.stream()
                .map(MemoryCapture::memoryId)
                .distinct()
                .toList())
            .stream()
            .filter(memory -> memory.isReadableBy(user.id(), user.groupId()))
            .collect(Collectors.toMap(Memory::id, Function.identity()));
        Map<Long, String> titles = replacements.titlesOf(user, rows.stream()
            .flatMap(row -> row.replacedMemoryIds().stream())
            .distinct()
            .toList());
        return rows.stream()
            .filter(row -> byId.containsKey(row.memoryId()))
            .map(row -> new CapturedMemory(row, byId.get(row.memoryId()), shownReplaced(user, row, byId.get(row.memoryId()))
                .stream()
                .filter(titles::containsKey)
                .map(titles::get)
                .toList()))
            .toList();
    }


    /**
     * 기록 줄에 보일 대신한 번호다. 아직 받아들이지 않은 제안은 받아들일 때 다시 판정하므로, 그 사이 다른 합치기로 대체되거나 바뀐 기억은 빼고 지금 대신할 수
     * 있는 번호만 보인다.
     */
    private List<Long> shownReplaced(CurrentUser user, MemoryCapture row, Memory memory) {
        if (row.replacedMemoryIds().isEmpty() || memory.status() != MemoryStatus.PROPOSED) {
            return row.replacedMemoryIds();
        }
        return replacements.replaceable(user, MemoryAccess.owner(), row.replacedMemoryIds(), memory.id());
    }


    /**
     * 기록 하나를 되돌린다. 새 항목은 지우고, 기존 제안의 승인은 물리고, 고친 항목은 이전 판으로 돌린다. 이미 되돌렸으면 그대로 둔다.
     *
     * @throws ApiException 남의 기록이거나 없으면 MEMORY_NOT_FOUND, 제안이면 MEMORY_REVISION_CONFLICT, 고친 뒤에 다시 바뀐
     * 항목이면 MEMORY_REVISION_CONFLICT
     */
    @Transactional
    public void undo(CurrentUser user, Long captureId) {
        MemoryCapture capture = captures.findByIdForUpdate(captureId).orElseThrow(MemoryCaptureService::notFound);
        if (!capture.userId().equals(user.id())) {
            throw notFound();
        }
        if (capture.undone()) {
            return;
        }
        switch (capture.kind()) {
            case PROPOSED -> throw conflict("a proposal is decided, not undone");
            case CREATED -> removeCreated(user, capture);
            case UPDATED -> restore(user, capture);
        }
        capture.undo(clock.instant());
        captures.save(capture);
        log.info("memory capture undone userId={} captureId={} kind={}", user.id(), captureId, capture.kind());
    }


    /**
     * 한 기억을 고친 기록 여럿을 한 번에 되돌린다. 가장 먼저 고치기 전의 판으로 돌리고 그 기록들을 모두 되돌린 것으로 적는다. 평가 전용 사용자의 평가 대화가 고친
     * 것을 비울 때 쓴다(docs/features/agent-quality/design.md 의 「비우기」). 하나씩 되돌리면 앞 기록을 되돌릴 때 판이 맞지 않아 막힌다.
     *
     * @param captureIds 같은 기억을 고친({@code UPDATED}) 요청자의 기록
     * @throws ApiException 남의 기록이거나 없거나 다른 기억의 기록이면 MEMORY_NOT_FOUND, 돌아갈 판이 없거나 읽을 수 없으면 MEMORY_REVISION_CONFLICT
     */
    @Transactional
    public void undoUpdates(CurrentUser user, Long memoryId, List<Long> captureIds) {
        List<MemoryCapture> rows = captures.findAllById(captureIds).stream()
            .filter(row -> !row.undone())
            .toList();
        if (rows.isEmpty()) {
            return;
        }
        for (MemoryCapture row : rows) {
            if (!row.userId().equals(user.id()) || !row.memoryId().equals(memoryId) || row.kind() != MemoryCaptureKind.UPDATED) {
                throw notFound();
            }
        }
        Memory memory = memories.findByIdForUpdate(memoryId).orElseThrow(MemoryCaptureService::notFound);
        if (!Objects.equals(memory.ownerUserId(), user.id()) || memory.scope() != MemoryScope.USER) {
            throw notFound();
        }
        int first = rows.stream().mapToInt(MemoryCapture::baseRevision).min().orElseThrow();
        MemoryRevision before = revisions
            .findById(new MemoryRevisionId(memoryId, first))
            .orElseThrow(() -> conflict("the previous revision is missing"));
        if (!before.readable()) {
            throw conflict("an unreadable revision is not restored here");
        }
        memoryService.revise(user, memory, before.content(), before.summary(), before.retrieval());
        for (MemoryCapture row : rows) {
            row.undo(clock.instant());
            captures.save(row);
        }
        log.info("memory updates undone userId={} memoryId={} count={}", user.id(), memoryId, rows.size());
    }


    /** 바로 저장을 되돌린다. 새 항목은 지우고 기존 제안은 복원한다. 그 뒤에 고쳤으면 막고, 이미 없으면 기록만 되돌린다. */
    private void removeCreated(CurrentUser user, MemoryCapture capture) {
        Optional<Memory> found = memories.findByIdForUpdate(capture.memoryId());
        if (found.isEmpty()) {
            return;
        }
        if (capture.baseRevision() != null && found.get().revision() != capture.baseRevision()) {
            throw conflict("the memory has changed after it was remembered");
        }
        if (capture.previousStatus() == MemoryStatus.PROPOSED) {
            Memory memory = found.get();
            if (!Objects.equals(memory.ownerUserId(), user.id()) || memory.scope() != MemoryScope.USER) {
                throw notFound();
            }
            if (memory.status() != MemoryStatus.ACCEPTED) {
                throw conflict("the memory status has changed after it was remembered");
            }
            memory.restoreProposal(clock.instant());
            Memory restored = memories.save(memory);
            memories.clearSupersededBy(memory.id());
            // 받아들임을 무른 것이다. 첫 반응은 그대로 두고, 오래 가는 선호로 읽히지 않게 사용자의 마지막 결정을 남긴다.
            feedback.record(MemoryProposalFeedback.of(
                user, restored, FeedbackEventType.DISMISSED, FeedbackActor.USER, clock.instant())
                .reason(FeedbackLabeler.MEMORY_UNDO));
        } else {
            memoryService.delete(user, capture.memoryId());
        }
    }


    private void restore(CurrentUser user, MemoryCapture capture) {
        Memory memory = memories.findByIdForUpdate(capture.memoryId()).orElseThrow(MemoryCaptureService::notFound);
        if (!Objects.equals(memory.ownerUserId(), user.id()) || memory.scope() != MemoryScope.USER) {
            throw notFound();
        }
        if (memory.revision() != capture.baseRevision() + 1) {
            throw conflict("the memory has changed after it was remembered");
        }
        MemoryRevision before = revisions
            .findById(new MemoryRevisionId(memory.id(), capture.baseRevision()))
            .orElseThrow(() -> conflict("the previous revision is missing"));
        if (!before.readable()) {
            throw conflict("an unreadable revision is not restored here");
        }
        memoryService.revise(user, memory, before.content(), before.summary(), before.retrieval());
    }


    /**
     * 고칠 항목은 요청자의 개인 MEMORY 항목이고 승인됐고 풀리며 이 에이전트가 받는 collection 에 있다. 바로 저장 조건이 아니면 고치지 않고 그 항목을
     * 대신할 제안으로 남긴다({@link #proposeUpdate}).
     */
    private MemoryRememberResult update(CurrentUser user, MemoryAccess access, MemoryRememberRequest request) {
        Optional<Memory> found = memories.findByIdForUpdate(request.memoryId());
        if (found.isEmpty() || !updatable(user, access, found.get()) || superseded(found.get())) {
            return MemoryRememberResult.of(MemoryRememberOutcome.UPDATE_TARGET_NOT_FOUND);
        }
        Memory memory = found.get();
        if (!request.direct()
            || request.sensitive()
            || MemorySensitiveHints.suspected(request.title(), request.content(), request.summary())) {
            return proposeUpdate(user, access, request, memory);
        }
        // 요약을 주지 않았으면 지금 요약을 둔다. 빈 글이면 지운다
        String current = memory.storedSummary() == null
            ? MemorySummaryBackfill.ruleSummary(memory, request.content(), memory.retrieval())
            : memory.summary();
        String summary = request.summary() == null ? current : request.summary();
        if (request.content().equals(memory.content()) && summary.equals(memory.summary())) {
            return new MemoryRememberResult(MemoryRememberOutcome.ALREADY_KNOWN, memory.id());
        }
        int base = memory.revision();
        try {
            // 암호화는 revision 을 남기기 전에 하므로 거절돼도 줄과 revision 이 바뀌지 않는다
            memoryService.revise(user, memory, request.content(), summary, memory.retrieval());
        } catch (ApiException ex) {
            if (ex.code() == ErrorCode.MEMORY_ENCRYPTION_UNAVAILABLE) {
                return MemoryRememberResult.of(MemoryRememberOutcome.ENCRYPTION_UNAVAILABLE);
            }
            throw ex;
        }
        record(memory.id(), user, request, MemoryCaptureKind.UPDATED, base);
        return new MemoryRememberResult(MemoryRememberOutcome.UPDATED, memory.id());
    }


    /**
     * 바로 고칠 수 없는 고치기를 고치기 제안으로 남긴다. 합치기 제안과 같은 모양이다. 새 제안의 {@code replaces} 가 고칠 항목 하나이고, 사용자가
     * 받아들이면 새 항목이 그 항목을 대신한다(「합치기」). 실패로 돌려주면 바깥 글을 읽은 대화에서는 사용자가 「바꿔 줘」 를 골라도 영영 고치지 못한다.
     *
     * <p>collection 과 자동 보관 허락, {@code ALWAYS} 꺼내기는 고칠 항목의 것을 그대로 둔다. 사람이 정한 값을 받아들이는 순간 바꾸지 않게 한다.
     */
    private MemoryRememberResult proposeUpdate(
        CurrentUser user, MemoryAccess access, MemoryRememberRequest request, Memory target) {
        MemoryRememberRequest proposal = new MemoryRememberRequest(
            request.title(),
            request.content(),
            request.summary(),
            target.collection(),
            request.sensitive(),
            null,
            request.executionId(),
            request.conversationId(),
            false,
            request.externalRead(),
            target.archivable(),
            List.of(target.id()));
        // 보관한 항목을 고치자고 했으면 다시 쓰일 사실이므로 색인으로 둔다
        MemoryRetrieval retrieval = target.retrieval() == MemoryRetrieval.ALWAYS ? MemoryRetrieval.ALWAYS : MemoryRetrieval.SEARCH;
        MemoryRememberResult result = create(user, access, proposal, new MemoryPlacement(target.collection(), retrieval), target.archivable());
        if (result.outcome() != MemoryRememberOutcome.PROPOSED) {
            return result;
        }
        return new MemoryRememberResult(
            MemoryRememberOutcome.UPDATE_PROPOSED, result.memoryId(), result.replaced(), result.notReplaced());
    }


    private static boolean updatable(CurrentUser user, MemoryAccess access, Memory memory) {
        return memory.scope() == MemoryScope.USER
            && Objects.equals(memory.ownerUserId(), user.id())
            && memory.entryType() == MemoryEntryType.MEMORY
            && memory.status() == MemoryStatus.ACCEPTED
            && memory.readable()
            && access.allows(memory.collection());
    }


    /**
     * 같은 제목과 본문의 줄이 이미 있을 때다. 승인 전의 제안을 사용자가 지금 직접 말했으면 받아들인 것으로 본다. 거절한 줄은 다시
     * 만들지 않는다. 새 항목을 만들지 않은 결과면 이번 호출의 {@code replaces} 는 모두 대신하지 못한 번호로 알린다(스펙 REQ6). 모델이
     * 합쳤다고 잘못 답하지 않게 한다.
     */
    private MemoryRememberResult existingOutcome(
        CurrentUser user, Memory existing, boolean direct, MemoryRememberRequest request) {
        MemoryRememberResult result = existingOutcomeOf(user, existing, direct, request);
        if (result.outcome() == MemoryRememberOutcome.REMEMBERED || request.replaces().isEmpty()) {
            return result;
        }
        return new MemoryRememberResult(result.outcome(), result.memoryId(), List.of(), notReplaced(request.replaces(), List.of()));
    }


    private MemoryRememberResult existingOutcomeOf(
        CurrentUser user, Memory existing, boolean direct, MemoryRememberRequest request) {
        return switch (existing.status()) {
            case ACCEPTED -> new MemoryRememberResult(
                superseded(existing) ? MemoryRememberOutcome.SUPERSEDED_BEFORE : MemoryRememberOutcome.ALREADY_KNOWN,
                existing.id());
            case REJECTED -> new MemoryRememberResult(MemoryRememberOutcome.REJECTED_BEFORE, existing.id());
            case PROPOSED -> {
                // 저장된 제안의 요약이 이번 호출의 요약과 다르거나 민감해 보이면 받아들이지 않는다. 대조를 거치지 않은 요약이 들어가지 않게 한다
                String requested = request.summary() == null ? "" : request.summary();
                if (!direct || !existing.summary().equals(requested)
                    || MemorySensitiveHints.suspected(existing.title(), existing.content(), existing.summary())) {
                    yield new MemoryRememberResult(MemoryRememberOutcome.ALREADY_PROPOSED, existing.id());
                }
                Memory memory = memoryService.accept(user, existing.id());
                record(memory.id(), user, request, MemoryCaptureKind.CREATED, memory.revision(), MemoryStatus.PROPOSED, List.of());
                // 합치기 번호는 제안 때 기록한 것을 쓴다. 이번 호출의 replaces 가운데 그 밖의 번호는 대신하지 못한 것으로 알린다
                List<Long> replaced = captures.findFirstByMemoryIdAndKindOrderByIdDesc(memory.id(), MemoryCaptureKind.PROPOSED)
                    .map(MemoryCapture::replacedMemoryIds)
                    .orElse(List.of());
                yield new MemoryRememberResult(
                    MemoryRememberOutcome.REMEMBERED, memory.id(), replaced, notReplaced(request.replaces(), replaced));
            }
        };
    }


    /** 다른 항목이 이 항목을 대신했다. 그 항목은 실리지 않으므로 같은 사실로 보거나 고칠 대상으로 삼지 않는다. */
    private static boolean superseded(Memory memory) {
        return memory.supersededByMemoryId() != null;
    }


    private void record(
        Long memoryId, CurrentUser user, MemoryRememberRequest request, MemoryCaptureKind kind, Integer base) {
        record(memoryId, user, request, kind, base, null, List.of());
    }


    private void record(
        Long memoryId,
        CurrentUser user,
        MemoryRememberRequest request,
        MemoryCaptureKind kind,
        Integer base,
        MemoryStatus previousStatus,
        List<Long> replaced) {
        captures.save(MemoryCapture.of(
            memoryId,
            user.id(),
            request.conversationId(),
            request.executionId(),
            kind,
            base,
            previousStatus,
            request.externalRead())
            .replacing(replaced));
        log.info(
            "memory captured userId={} memoryId={} executionId={} kind={} replaced={}",
            user.id(),
            memoryId,
            request.executionId(),
            kind,
            replaced);
    }


    private static ApiException notFound() {
        return new ApiException(ErrorCode.MEMORY_NOT_FOUND, "no such memory capture");
    }


    private static ApiException conflict(String message) {
        return new ApiException(ErrorCode.MEMORY_REVISION_CONFLICT, message);
    }
}
