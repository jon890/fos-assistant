package com.bifos.assistant.quality.application;

import com.bifos.assistant.chat.artifact.application.ArtifactVaultFinder;
import com.bifos.assistant.chat.artifact.application.ArtifactVaultService;
import com.bifos.assistant.chat.turn.application.ChatService;
import com.bifos.assistant.memory.application.MemoryService;
import com.bifos.assistant.memory.domain.entity.Memory;
import com.bifos.assistant.quality.config.QualityProperties;
import com.bifos.assistant.quality.domain.FeatureResult;
import com.bifos.assistant.quality.domain.QualityRun;
import com.bifos.assistant.quality.domain.type.QualityRunStatus;
import com.bifos.assistant.quality.infra.QualityRunRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.config.LiveProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.user.domain.AppUser;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
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
    private final EvaluationUserService evaluationUsers;
    private final QualityRunRepository runs;
    private final DeployCheckRuns runWrites;
    private final LiveProperties<QualityProperties> properties;
    private final Clock clock;


    /** 그 평가 대화와, 그 대화에서 파일 공간에 넣은 보관본을 지운다. 첨부와 결과물은 대화 정리가 함께 지운다. */
    void dropConversation(CurrentUser evaluation, Long conversationId) {
        try {
            for (Long id : vaultFinder.putIdsOf(evaluation.id(), conversationId)) {
                vault.delete(evaluation.id(), id);
            }
            chat.delete(evaluation, conversationId);
        } catch (RuntimeException ex) {
            log.warn("배포 뒤 확인의 평가 대화를 지우지 못했다 conversationId={}", conversationId, ex);
        }
    }


    /** 공개 식별자로 평가 대화를 찾아 지운다. 이미 지웠으면 아무것도 하지 않는다. */
    void dropConversation(CurrentUser evaluation, UUID conversationPublicId) {
        Long conversationId;
        try {
            conversationId = chat.conversationOf(evaluation, conversationPublicId).id();
        } catch (ApiException ignored) {
            return;
        }
        dropConversation(evaluation, conversationId);
    }


    /**
     * 확인이 시작된 뒤 평가 사용자에게 생긴 기억을 지운다. 미리 넣은 한 줄은 남긴다. 문항이 돌다 기억 도구로 생길 수 있다.
     *
     * @param since 확인을 시작한 시각
     */
    void forgetNewMemories(CurrentUser evaluation, Instant since) {
        List<Memory> created = Stream.concat(
            memories.readableBy(evaluation).stream(), memories.proposalsOf(evaluation).stream())
            .filter(memory -> evaluation.id().equals(memory.ownerUserId()))
            .filter(memory -> memory.createdAt() != null && !memory.createdAt().isBefore(since))
            .filter(memory -> !DeployCheckItems.MEMORY_TITLE.equals(memory.title()))
            .distinct()
            .toList();
        for (Memory memory : created) {
            try {
                memories.delete(evaluation, memory.id());
            } catch (RuntimeException ex) {
                log.warn("배포 뒤 확인이 만든 기억을 지우지 못했다 memoryId={}", memory.id(), ex);
            }
        }
    }


    /** 보관 기간이 지난 실패 기능의 평가 대화를 지우고, 그 줄의 대화·실행 링크를 비운다. 줄과 숫자는 남는다. */
    @Scheduled(cron = "${assistant.quality.cleanup-cron}")
    void sweepExpired() {
        Instant before = clock.instant().minus(properties.current().linkRetention());
        for (QualityRun run : runs.findByStatusAndStartedAtBefore(QualityRunStatus.FAILED, before)) {
            List<UUID> conversations = run.results().stream()
                .map(FeatureResult::conversationPublicId)
                .filter(publicId -> publicId != null)
                .toList();
            boolean linked = run.results().stream().anyMatch(result -> !result.executionIds().isEmpty());
            if (conversations.isEmpty() && !linked) {
                continue;
            }
            Optional<AppUser> evaluation = evaluationUsers.find(run.groupId());
            if (evaluation.isPresent()) {
                CurrentUser user = EvaluationUserService.asCurrentUser(evaluation.get());
                conversations.forEach(publicId -> dropConversation(user, publicId));
            }
            runWrites.dropLinks(run.id());
        }
    }
}
