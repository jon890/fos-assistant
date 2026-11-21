package com.bifos.assistant.quality.application;

import com.bifos.assistant.chat.artifact.application.ArtifactVaultFinder;
import com.bifos.assistant.chat.artifact.application.ArtifactVaultService;
import com.bifos.assistant.chat.turn.application.ChatService;
import com.bifos.assistant.memory.application.MemoryCaptureService;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.application.dto.CapturedMemory;
import com.bifos.assistant.memory.domain.entity.MemoryCapture;
import com.bifos.assistant.memory.domain.type.MemoryCaptureKind;
import com.bifos.assistant.memory.domain.type.MemoryStatus;
import com.bifos.assistant.quality.config.QualityProperties;
import com.bifos.assistant.quality.domain.FeatureResult;
import com.bifos.assistant.quality.domain.QualityRun;
import com.bifos.assistant.quality.domain.type.QualityRunKind;
import com.bifos.assistant.quality.domain.type.QualityRunStatus;
import com.bifos.assistant.quality.infra.QualityRunRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.user.domain.AppUser;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 배포 뒤 확인이 평가 전용 사용자에게 만든 것을 지운다(docs/features/agent-quality/design.md 의 「기능별 문항과 판정」).
 *
 * <p>통과한 기능의 대화와 만든 것은 바로, 실패한 기능의 것은 보관 기간 뒤 하루 한 번 지운다. 실행 기록 줄은 지우지 않는다(rule-49).
 * 지우다 실패해도 확인 결과는 바꾸지 않고 경고 로그만 남긴다. 하루 정리가 다시 지운다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class DeployCheckCleaner {

    private final ChatService chat;
    private final ArtifactVaultService vault;
    private final ArtifactVaultFinder vaultFinder;
    private final MemoryService memories;
    private final MemoryCaptureService captures;
    private final EvaluationUserService evaluationUsers;
    private final QualityRunRepository runs;
    private final DeployCheckRuns runWrites;
    private final LiveProperties<QualityProperties> properties;
    private final Clock clock;


    /**
     * 그 평가 대화와, 그 대화에서 파일 공간에 넣은 보관본을 지운다. 첨부와 결과물은 대화 정리가 함께 지운다.
     *
     * @return 지웠으면 참. 실패하면 경고 로그만 남기고 거짓이다
     */
    boolean dropConversation(CurrentUser evaluation, Long conversationId) {
        try {
            for (Long id : vaultFinder.putIdsOf(evaluation.id(), conversationId)) {
                vault.delete(evaluation.id(), id);
            }
            chat.delete(evaluation, conversationId);
            return true;
        } catch (RuntimeException ex) {
            log.warn("배포 뒤 확인의 평가 대화를 지우지 못했다 conversationId={}", conversationId, ex);
            return false;
        }
    }


    /**
     * 공개 식별자로 평가 대화를 찾아 지운다.
     *
     * @return 지웠거나 이미 없으면 참
     */
    boolean dropConversation(CurrentUser evaluation, UUID conversationPublicId) {
        Long conversationId;
        try {
            conversationId = chat.conversationOf(evaluation, conversationPublicId).id();
        } catch (ApiException ignored) {
            return true;
        }
        return dropConversation(evaluation, conversationId);
    }


    /**
     * 그 대화에서 기억 도구로 남긴 것을 되돌린다. 준비 때 넣은 기억을 문항이 고쳤어도 되돌아온다.
     *
     * <ul>
     * <li>고치기만 한 기억은 그 대화가 고친 기록을 한 번에 되돌려 가장 먼저 고치기 전의 판으로 돌린다. 하나씩 되돌리면 판이 맞지 않아 막힌다.
     * <li>남긴 제안은 지운다.
     * <li>새로 만든 기억은 지운다. 다른 대화의 제안을 받아들여 만든 것이면 지우지 않고 제안으로 돌린다. 같은 대화가 그 뒤에 고쳤으면 판이 맞지 않아 지운다.
     * </ul>
     *
     * <p>시각이 아니라 대화로 찾는다. 함께 도는 다른 확인이나 평가가 만든 기억을 지우지 않는다.
     *
     * @return 모두 되돌렸으면 참. 하나라도 실패하면 경고 로그를 남기고 거짓이다
     */
    boolean forgetCaptured(CurrentUser evaluation, Long conversationId) {
        List<CapturedMemory> captured;
        try {
            captured = captures.capturesOf(evaluation, conversationId);
        } catch (RuntimeException ex) {
            log.warn("평가 대화의 기억 기록을 읽지 못했다 conversationId={}", conversationId, ex);
            return false;
        }
        Map<Long, List<CapturedMemory>> byMemory = new LinkedHashMap<>();
        for (CapturedMemory row : captured) {
            byMemory.computeIfAbsent(row.memory().id(), id -> new ArrayList<>()).add(row);
        }
        boolean all = true;
        for (Map.Entry<Long, List<CapturedMemory>> entry : byMemory.entrySet()) {
            try {
                forget(evaluation, entry.getKey(), entry.getValue());
            } catch (RuntimeException ex) {
                all = false;
                log.warn("평가 대화가 남긴 기억을 되돌리지 못했다 memoryId={}", entry.getKey(), ex);
            }
        }
        return all;
    }


    private void forget(CurrentUser evaluation, Long memoryId, List<CapturedMemory> rows) {
        List<Long> updates = rows.stream()
            .filter(row -> row.capture().kind() == MemoryCaptureKind.UPDATED)
            .map(row -> row.capture().id())
            .toList();
        Optional<CapturedMemory> made = rows.stream()
            .filter(row -> row.capture().kind() != MemoryCaptureKind.UPDATED)
            .findFirst();
        if (made.isEmpty()) {
            captures.undoUpdates(evaluation, memoryId, updates);
            return;
        }
        MemoryCapture first = made.get().capture();
        boolean acceptedProposal = first.kind() == MemoryCaptureKind.CREATED && first.previousStatus() == MemoryStatus.PROPOSED;
        if (acceptedProposal && updates.isEmpty()) {
            captures.undo(evaluation, first.id());
            return;
        }
        memories.delete(evaluation, memoryId);
    }


    /** 보관 기간이 지난 실패 기능의 평가 대화를 지우고, 그 줄의 대화·실행 링크를 비운다. 줄과 숫자는 남는다. */
    @Scheduled(cron = "${assistant.quality.cleanup-cron}")
    void sweepExpired() {
        Instant before = clock.instant().minus(properties.current().linkRetention());
        for (QualityRun run : runs.findByKindAndStatusAndStartedAtBefore(QualityRunKind.DEPLOY_CHECK, QualityRunStatus.FAILED, before)) {
            List<UUID> conversations = run.results().stream()
                .map(FeatureResult::conversationPublicId)
                .filter(publicId -> publicId != null)
                .toList();
            boolean linked = run.results().stream().anyMatch(result -> !result.executionIds().isEmpty());
            if (conversations.isEmpty() && !linked) {
                continue;
            }
            Optional<AppUser> evaluation = evaluationUsers.find(run.groupId());
            Set<UUID> removed = new HashSet<>();
            if (evaluation.isPresent()) {
                CurrentUser user = EvaluationUserService.asCurrentUser(evaluation.get());
                conversations.stream().filter(publicId -> dropConversation(user, publicId)).forEach(removed::add);
            } else {
                // 평가 사용자를 지울 때 그 대화도 함께 지워졌다.
                removed.addAll(conversations);
            }
            runWrites.dropLinks(run.id(), removed);
        }
    }
}
