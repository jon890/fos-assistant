package com.bifos.assistant.memory.application;

import com.bifos.assistant.feedback.application.DecisionFeedbackRecorder;
import com.bifos.assistant.feedback.application.FeedbackLabeler;
import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.memory.application.model.CapturedMemory;
import com.bifos.assistant.memory.application.model.MemoryAccess;
import com.bifos.assistant.memory.application.model.MemoryRememberOutcome;
import com.bifos.assistant.memory.application.model.MemoryRememberRequest;
import com.bifos.assistant.memory.application.model.MemoryRememberResult;
import com.bifos.assistant.memory.domain.Memory;
import com.bifos.assistant.memory.domain.MemoryCapture;
import com.bifos.assistant.memory.domain.MemoryPlacement;
import com.bifos.assistant.memory.domain.MemoryRevision;
import com.bifos.assistant.memory.domain.MemoryRevisionId;
import com.bifos.assistant.memory.domain.StoredContent;
import com.bifos.assistant.memory.domain.type.MemoryCaptureKind;
import com.bifos.assistant.memory.domain.type.MemoryEntryType;
import com.bifos.assistant.memory.domain.type.MemoryRetrieval;
import com.bifos.assistant.memory.domain.type.MemoryScope;
import com.bifos.assistant.memory.domain.type.MemorySensitivity;
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
 * 민감도와 collection 과 한 실행의 상한을 다시 본다. 민감 항목은 바로 저장 조건이어도 제안이다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemoryCaptureService {

    /** 한 실행이 남길 수 있는 기록의 수다. 바로 저장과 제안을 함께 센다. */
    public static final int PER_EXECUTION_MAX = 3;

    private final MemoryService memoryService;
    private final MemoryRepository memories;
    private final MemoryRevisionRepository revisions;
    private final MemoryCaptureRepository captures;
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
        MemorySensitivity sensitivity = request.sensitivity();
        boolean direct = request.direct() && sensitivity == MemorySensitivity.NORMAL;
        String dedupKey = MemoryService.proposalDedupKey(user.id(), request.title(), request.content());
        if (sensitivity == MemorySensitivity.NORMAL) {
            Optional<Memory> existing = memories.findByProposalDedupKey(dedupKey);
            if (existing.isPresent()) {
                return existingOutcome(user, existing.get(), direct, request);
            }
        }
        MemoryPlacement placement = new MemoryPlacement(collection, MemoryRetrieval.SEARCH, sensitivity);
        StoredContent body;
        try {
            body = memoryService.stored(request.content(), sensitivity, "USER:" + user.id());
        } catch (ApiException ex) {
            if (ex.code() == ErrorCode.MEMORY_ENCRYPTION_UNAVAILABLE) {
                return MemoryRememberResult.of(MemoryRememberOutcome.ENCRYPTION_UNAVAILABLE);
            }
            throw ex;
        }
        Memory saved = memories.save(
                direct
                        ? Memory.remembered(
                                user.id(),
                                request.title(),
                                body,
                                placement,
                                request.executionId(),
                                dedupKey,
                                clock.instant())
                        : Memory.proposed(
                                user.id(),
                                request.title(),
                                body,
                                placement,
                                request.executionId(),
                                dedupKey,
                                clock.instant()));
        MemoryCaptureKind kind = direct ? MemoryCaptureKind.CREATED : MemoryCaptureKind.PROPOSED;
        record(saved.id(), user, request, kind, direct ? saved.revision() : null);
        if (!direct) {
            // 바로 저장은 사용자 본인의 말이라 제안이 아니다. 제안만 판단 피드백의 SURFACED 로 남긴다.
            feedback.record(MemoryService.proposalFeedback(
                    user, saved, FeedbackEventType.SURFACED, FeedbackActor.AGENT, clock.instant()));
        }
        return new MemoryRememberResult(
                direct ? MemoryRememberOutcome.REMEMBERED : MemoryRememberOutcome.PROPOSED, saved.id());
    }

    /**
     * 한 대화에서 요청자가 남긴 기록과 그 항목을 만든 순으로 낸다. 되돌린 기록과 항목이 없어진 기록은 뺀다.
     *
     * @param conversationId 요청자의 대화임을 부르는 쪽이 확인한 번호
     */
    public List<CapturedMemory> capturesOf(CurrentUser user, Long conversationId) {
        List<MemoryCapture> rows =
                captures.findByUserIdAndConversationIdAndUndoneAtIsNullOrderByIdAsc(user.id(), conversationId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, Memory> byId =
                memories
                        .findAllById(rows.stream()
                                .map(MemoryCapture::memoryId)
                                .distinct()
                                .toList())
                        .stream()
                        .filter(memory -> memory.isReadableBy(user.id(), user.groupId()))
                        .collect(Collectors.toMap(Memory::id, Function.identity()));
        return rows.stream()
                .filter(row -> byId.containsKey(row.memoryId()))
                .map(row -> new CapturedMemory(row, byId.get(row.memoryId())))
                .toList();
    }

    /**
     * 기록 하나를 되돌린다. 새 항목은 지우고, 기존 제안의 승인은 물리고, 고친 항목은 이전 판으로 돌린다. 이미 되돌렸으면 그대로 둔다.
     *
     * @throws ApiException 남의 기록이거나 없으면 MEMORY_NOT_FOUND, 제안이면 MEMORY_REVISION_CONFLICT, 고친 뒤에 다시 바뀐
     *     항목이면 MEMORY_REVISION_CONFLICT
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
            // 받아들임을 무른 것이다. 첫 반응은 그대로 두고, 오래 가는 선호로 읽히지 않게 사용자의 마지막 결정을 남긴다.
            feedback.record(MemoryService.proposalFeedback(
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
        String content = before.contentKeyId() == null ? before.content() : null;
        if (content == null) {
            throw conflict("a sealed revision is not restored here");
        }
        memoryService.revise(user, memory, content, before.retrieval(), before.sensitivity());
    }

    /** 고칠 항목은 요청자의 개인 MEMORY 항목이고 승인됐고 일반 민감도이며 이 에이전트가 받는 collection 에 있다. */
    private MemoryRememberResult update(CurrentUser user, MemoryAccess access, MemoryRememberRequest request) {
        if (!request.direct() || request.sensitivity() != MemorySensitivity.NORMAL) {
            return MemoryRememberResult.of(MemoryRememberOutcome.UPDATE_NEEDS_CONFIRMATION);
        }
        Optional<Memory> found = memories.findByIdForUpdate(request.memoryId());
        if (found.isEmpty() || !updatable(user, access, found.get())) {
            return MemoryRememberResult.of(MemoryRememberOutcome.UPDATE_TARGET_NOT_FOUND);
        }
        Memory memory = found.get();
        if (request.content().equals(memory.content())) {
            return new MemoryRememberResult(MemoryRememberOutcome.ALREADY_KNOWN, memory.id());
        }
        int base = memory.revision();
        memoryService.revise(user, memory, request.content(), memory.retrieval(), MemorySensitivity.NORMAL);
        record(memory.id(), user, request, MemoryCaptureKind.UPDATED, base);
        return new MemoryRememberResult(MemoryRememberOutcome.UPDATED, memory.id());
    }

    private static boolean updatable(CurrentUser user, MemoryAccess access, Memory memory) {
        return memory.scope() == MemoryScope.USER
                && Objects.equals(memory.ownerUserId(), user.id())
                && memory.entryType() == MemoryEntryType.MEMORY
                && memory.status() == MemoryStatus.ACCEPTED
                && memory.sensitivity() == MemorySensitivity.NORMAL
                && !memory.sealed()
                && access.allows(memory.collection(), MemorySensitivity.NORMAL);
    }

    /**
     * 같은 제목과 본문의 줄이 이미 있을 때다. 승인 전의 제안을 사용자가 지금 직접 말했으면 받아들인 것으로 본다. 거절한 줄은 다시
     * 만들지 않는다.
     */
    private MemoryRememberResult existingOutcome(
            CurrentUser user, Memory existing, boolean direct, MemoryRememberRequest request) {
        return switch (existing.status()) {
            case ACCEPTED -> new MemoryRememberResult(MemoryRememberOutcome.ALREADY_KNOWN, existing.id());
            case REJECTED -> new MemoryRememberResult(MemoryRememberOutcome.REJECTED_BEFORE, existing.id());
            case PROPOSED -> {
                if (!direct) {
                    yield new MemoryRememberResult(MemoryRememberOutcome.ALREADY_PROPOSED, existing.id());
                }
                Memory memory = memoryService.accept(user, existing.id());
                record(memory.id(), user, request, MemoryCaptureKind.CREATED, memory.revision(), MemoryStatus.PROPOSED);
                yield new MemoryRememberResult(MemoryRememberOutcome.REMEMBERED, memory.id());
            }
        };
    }

    private void record(
            Long memoryId, CurrentUser user, MemoryRememberRequest request, MemoryCaptureKind kind, Integer base) {
        record(memoryId, user, request, kind, base, null);
    }

    private void record(
            Long memoryId,
            CurrentUser user,
            MemoryRememberRequest request,
            MemoryCaptureKind kind,
            Integer base,
            MemoryStatus previousStatus) {
        captures.save(MemoryCapture.of(
                memoryId,
                user.id(),
                request.conversationId(),
                request.executionId(),
                kind,
                base,
                previousStatus,
                clock.instant()));
        log.info(
                "memory captured userId={} memoryId={} executionId={} kind={}",
                user.id(),
                memoryId,
                request.executionId(),
                kind);
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.MEMORY_NOT_FOUND, "no such memory capture");
    }

    private static ApiException conflict(String message) {
        return new ApiException(ErrorCode.MEMORY_REVISION_CONFLICT, message);
    }
}
