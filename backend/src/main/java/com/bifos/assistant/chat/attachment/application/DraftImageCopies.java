package com.bifos.assistant.chat.attachment.application;

import com.bifos.assistant.chat.attachment.config.AttachmentProperties;
import com.bifos.assistant.chat.attachment.domain.ChatAttachment;
import com.bifos.assistant.chat.attachment.infra.AttachmentStore;
import com.bifos.assistant.chat.attachment.infra.ChatAttachmentRepository;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 만든 그림을 블로그 원고에 넣으려고 만드는 사진 첨부 사본이다(ADR-20261120 / generated-image-draft).
 *
 * <p>사본은 보통 사진과 같은 폴더와 이름 규칙을 써서 원고와 미리보기, 플러그인의 저장 worker 가 고칠 것 없이 읽는다.
 * 메시지에는 묶지 않아 대화 화면과 사진 순번에 나오지 않는다. 보관 기간은 사진을 올릴 때와 같다.
 *
 * <p>부르는 쪽이 요청자가 대화 주인임을 확인하고 {@code ChatContentMutationCoordinator} 의 트랜잭션 안에서 부른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DraftImageCopies {
    private final ChatAttachmentRepository attachments;
    private final AttachmentStore store;
    private final AttachmentProperties properties;
    private final Clock clock;


    /**
     * 그 그림으로 만든 사본 가운데 아직 쓸 수 있는 것을 찾는다. 지우지 않았고 보관 기간 안이고 파일이 있어야 한다.
     */
    public Optional<ChatAttachment> findUsable(Long ownerUserId, Long conversationId, Long artifactId) {
        Instant now = clock.instant();
        return attachments.findByConversationIdAndSourceArtifactIdOrderByIdDesc(conversationId, artifactId).stream()
            .filter(it -> ownerUserId.equals(it.uploadedByUserId()) && it.messageId() == null && it.isVisible()
                && it.expiresAt().isAfter(now) && fileExists(it))
            .findFirst();
    }


    /**
     * 그림 바이트로 사본 행과 파일을 만든다. 행 번호를 받은 뒤 파일을 쓰고, 트랜잭션이 되돌려지면 쓴 파일을 지운다.
     *
     * @param contentType 그림의 머리 바이트로 정한 형식. 사진 첨부가 받는 형식이어야 한다
     * @param title 그림 제목. 올릴 때의 파일 이름 자리에 확장자를 붙여 둔다
     */
    public ChatAttachment create(Long ownerUserId, Long conversationId, Long artifactId, String title,
        String contentType, byte[] bytes) {
        String extension = AttachmentStore.extensionFor(contentType)
            .orElseThrow(() -> new ApiException(ErrorCode.IMAGE_SOURCE_GONE, "this image can not be used as a photo"));
        ChatAttachment copy = attachments.save(ChatAttachment.draftImageCopy(conversationId, ownerUserId, artifactId,
            originalName(title, extension), contentType, bytes.length,
            clock.instant().plus(Duration.ofDays(properties.retentionDays()))));
        copy.nameStoredFile(AttachmentStore.storedName(copy.id(), extension));
        attachments.flush();
        store.save(copy, new ByteArrayInputStream(bytes));
        deleteOnRollback(copy);
        return copy;
    }


    /** Hermes 와 실행 공간에서 보이는 그 대화의 사진 폴더다. 실행 입력의 사진 참조 목록 위에 적는 폴더와 같다. */
    public String agentDirectory(Long ownerUserId, Long conversationId) {
        return properties.agentRoot().replaceAll("/+$", "") + "/users/"
            + AttachmentStore.userDirectoryKey(ownerUserId) + "/" + conversationId;
    }


    private boolean fileExists(ChatAttachment copy) {
        try (InputStream ignored = store.open(copy)) {
            return true;
        } catch (ApiException | IOException | UncheckedIOException ex) {
            return false;
        }
    }


    private void deleteOnRollback(ChatAttachment copy) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    return;
                }
                try {
                    store.delete(copy);
                } catch (RuntimeException ex) {
                    // 남은 파일은 행이 없어 아무도 읽지 않는다. 경로를 로그에 싣지 않는다.
                    log.warn("되돌린 그림 사본 파일을 지우지 못했다 attachmentId={}", copy.id());
                }
            }
        });
    }


    private static String originalName(String title, String extension) {
        String name = title + "." + extension;
        return name.length() <= 255 ? name : name.substring(0, 255 - extension.length() - 1) + "." + extension;
    }
}
