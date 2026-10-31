package com.bifos.assistant.chat.artifact.application;

import com.bifos.assistant.chat.artifact.application.dto.ArtifactAutoVaultStatus;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactVaultItem;
import com.bifos.assistant.chat.artifact.domain.ChatArtifact;
import com.bifos.assistant.chat.artifact.domain.type.AutoVaultState;
import com.bifos.assistant.chat.artifact.infra.ChatArtifactRepository;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.conversation.infra.ConversationRepository;
import com.bifos.assistant.shared.concurrent.BackgroundTasks;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.user.application.ArtifactAutoVaultSetting;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * turn 이 끝날 때 그 답에 묶인 결과물을 대화 요청자의 보관함에 자동으로 보관한다(#530 PR 3).
 *
 * <p>작업 기록은 결과물 행 자신의 {@code auto_vault_*} 칸이다. 새 표를 두지 않는다. 답을 저장한 뒤 행을 {@code PENDING}
 * 으로 두고 요청 밖 스레드에서 한 줄씩 맡아 보관한다. 맡기는 조건부 update 라 같은 turn 의 완료가 두 번 와도, 훑기와 겹쳐도
 * 한 번만 돈다. 같은 보관본은 {@link ArtifactVaultService} 의 지문이 한 건으로 만든다.
 *
 * <p>재시작하면 {@code RUNNING} 으로 남은 줄을 다시 차례에 두고, 차례를 기다리는 줄을 주기마다 훑는다. 보관이 실패해도 답과
 * 원본 파일은 그대로다. 보관 주인은 대화 요청자이고 에이전트 주인이 아니다. 취소되거나 답이 없는 turn 은 부르지 않는다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ArtifactAutoVault {

    /** 일시 장애로 다시 차례에 둘 때의 시도 상한이다. 넘으면 실패로 두고 사용자가 다시 보관한다. */
    static final int MAX_ATTEMPTS = 3;

    private final ChatArtifactRepository rows;
    private final ChatArtifactWriter writer;
    private final ArtifactVaultService vault;
    private final ConversationRepository conversations;
    private final ArtifactAutoVaultSetting setting;
    private final BackgroundTasks backgroundTasks;
    private final AtomicBoolean sweeping = new AtomicBoolean();


    /**
     * 완료한 turn 의 답에 묶인 결과물을 자동 보관 차례에 두고 요청 밖에서 보관을 시작한다.
     *
     * <p>요청자가 자동 보관을 끄면 아무것도 하지 않는다. 밖으로 던지지 않는다. 답은 이미 저장됐고 turn 은 그대로 끝나야 한다.
     */
    public void afterCompletedTurn(Long conversationId, Long messageId) {
        if (messageId == null) {
            return;
        }
        try {
            Long owner = ownerOf(conversationId).orElse(null);
            if (owner == null || !setting.isEnabled(owner)) {
                return;
            }
            if (writer.markAutoVaultPending(messageId) == 0) {
                return;
            }
            backgroundTasks.start("artifact-auto-vault", () -> runMessage(messageId));
        } catch (RuntimeException ex) {
            log.warn("could not queue artifacts to keep conversationId={} messageId={}", conversationId, messageId, ex);
        }
    }


    /** 재시작하면 맡은 작업이 없는 줄을 다시 차례에 두고 한 번 훑는다. */
    @EventListener(ApplicationReadyEvent.class)
    public void resumeAfterRestart() {
        try {
            int requeued = writer.requeueRunningAutoVault();
            if (requeued > 0) {
                log.info("artifact auto vault requeued after restart rows={}", requeued);
            }
        } catch (RuntimeException ex) {
            log.warn("could not requeue artifacts to keep after restart", ex);
            return;
        }
        sweepInBackground();
    }


    /** 차례를 기다리는 줄을 주기마다 훑는다. 앞 훑기가 아직 돌면 건너뛴다. */
    @Scheduled(
        fixedDelayString = "${assistant.artifact-vault.auto-retry-delay:60s}",
        initialDelayString = "${assistant.artifact-vault.auto-retry-delay:60s}")
    public void sweepInBackground() {
        if (!sweeping.compareAndSet(false, true)) {
            return;
        }
        backgroundTasks.start("artifact-auto-vault-sweep", () -> {
            try {
                sweep();
            } catch (RuntimeException ex) {
                log.warn("artifact auto vault sweep failed exceptionClass={}", ex.getClass().getSimpleName());
            } finally {
                sweeping.set(false);
            }
        });
    }


    /**
     * 차례를 기다리는 줄을 모두 돈다. 번호 순으로 한 번 지나가므로 다시 차례에 둔 줄은 다음 훑기가 맡는다.
     *
     * @return 맡아서 돌린 줄 수
     */
    public int sweep() {
        int handled = 0;
        long after = 0;
        while (true) {
            List<ChatArtifact> pending = rows.findTop100ByAutoVaultStateAndIdGreaterThanOrderByIdAsc(AutoVaultState.PENDING, after);
            if (pending.isEmpty()) {
                return handled;
            }
            for (ChatArtifact row : pending) {
                after = row.id();
                if (process(row.id(), false)) {
                    handled++;
                }
            }
        }
    }


    /**
     * 사용자가 「다시 보관」 을 누른 결과물을 지금 보관하고 끝난 상태를 준다. 대화 주인 확인은 부르는 쪽이 한다.
     *
     * <p>실패한 줄만 처음 차례로 되돌린다. 다른 상태면 그 상태를 그대로 준다. 사용자가 고른 것이라 지운 같은 보관본도 다시
     * 만든다. 자동 보관을 꺼도 다시 보관할 수 있다.
     */
    public ArtifactAutoVaultStatus retry(Long conversationId, Long messageId, String path) {
        ChatArtifact row = rows.findByMessageIdAndPathAndVaultUserIdIsNull(messageId, path)
            .filter(found -> found.conversationId().equals(conversationId))
            .orElseThrow(() -> new ApiException(ErrorCode.ARTIFACT_NOT_FOUND, "this file does not exist"));
        if (writer.reopenAutoVault(row.id())) {
            process(row.id(), true);
        }
        ChatArtifact now = rows.findById(row.id()).orElse(row);
        return new ArtifactAutoVaultStatus(now.autoVaultState(), now.autoVaultError());
    }


    private void runMessage(Long messageId) {
        for (ChatArtifact row : rows.findByMessageIdAndAutoVaultState(messageId, AutoVaultState.PENDING)) {
            process(row.id(), false);
        }
    }


    /**
     * 줄 하나를 맡아 보관한다. 다른 작업이 먼저 맡았으면 거짓이다.
     *
     * <p>보관할 자리가 모자라거나 예상하지 못한 오류면 다시 차례에 두고, 시도 상한을 넘으면 실패로 둔다. 거절된 결과물(받지
     * 않는 형식, 용량 초과, 원본이 지워짐)은 바로 실패로 둔다.
     */
    private boolean process(Long id, boolean revive) {
        if (!writer.claimAutoVault(id)) {
            return false;
        }
        ChatArtifact row = rows.findById(id).orElse(null);
        if (row == null) {
            return true;
        }
        try {
            Long owner = ownerOf(row.conversationId())
                .orElseThrow(() -> new ApiException(ErrorCode.ARTIFACT_NOT_FOUND, "the conversation is gone"));
            Optional<ArtifactVaultItem> kept = revive
                ? Optional.of(vault.vault(owner, row.conversationId(), row.path()))
                : vault.vaultAutomatically(owner, row.conversationId(), row.path());
            writer.finishAutoVault(id, kept.isPresent() ? AutoVaultState.DONE : AutoVaultState.SKIPPED, null);
        } catch (ApiException ex) {
            if (ex.code() == ErrorCode.ARTIFACT_VAULT_UNAVAILABLE) {
                retryLater(row, ex.code());
            } else {
                writer.finishAutoVault(id, AutoVaultState.FAILED, ex.code().name());
            }
        } catch (RuntimeException ex) {
            log.warn("could not keep an artifact automatically id={}", id, ex);
            retryLater(row, ErrorCode.ARTIFACT_VAULT_UNAVAILABLE);
        }
        return true;
    }


    private void retryLater(ChatArtifact row, ErrorCode code) {
        // 맡을 때 시도 횟수를 더했으므로 이 줄의 값이 지금까지 시도한 수다.
        AutoVaultState next = row.autoVaultAttempts() >= MAX_ATTEMPTS ? AutoVaultState.FAILED : AutoVaultState.PENDING;
        writer.finishAutoVault(row.id(), next, code.name());
    }


    /** 대화 요청자다. 대화를 지웠으면 빈 값이다. 그룹 공개 에이전트를 써도 에이전트 주인이 아니라 대화 주인이다. */
    private Optional<Long> ownerOf(Long conversationId) {
        return conversations.findById(conversationId)
            .filter(conversation -> conversation.deletedAt() == null)
            .map(Conversation::userId);
    }
}
