package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.infra.AttachmentStore;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대화에 올린 사진을 받고 판정하고 행을 만든다.
 *
 * <p>첨부는 대화를 통해서만 닿는다. 먼저 대화의 주인인지 보고, 첨부는 언제나 첨부 번호와 대화 번호를
 * 함께 써서 찾는다. 대화 주인만 확인하고 번호로만 찾으면 내 대화 번호에 남의 첨부 번호를 붙여 읽을 수
 * 있다. 근거는 ADR-020 에 있다.
 */
@Service
@RequiredArgsConstructor
public class AttachmentService {

    private static final int ORIGINAL_NAME_LIMIT = 255;
    private static final String FALLBACK_NAME = "image";

    private final ConversationAccess access;
    private final ChatAttachmentRepository attachments;
    private final AttachmentStore store;
    private final AttachmentProperties properties;

    /**
     * 사진 한 장을 올린다.
     *
     * <p>행을 먼저 만들어 번호를 얻고 그 번호로 파일을 쓴다. 파일 쓰기가 실패하면 예외로 트랜잭션을
     * 되돌려 행을 남기지 않는다. 파일 없는 행이 남으면 화면이 깨진 사진을 보인다.
     */
    @Transactional
    public ChatAttachment upload(
            CurrentUser user,
            Long conversationId,
            String originalName,
            String contentType,
            long byteSize,
            InputStreamSource body) {
        access.requireOwn(user, conversationId);
        String normalizedType = normalize(contentType);
        String extension = AttachmentStore.extensionFor(normalizedType)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.VALIDATION_FAILED, "only jpeg, png, gif and webp images are accepted"));
        if (byteSize > properties.maxBytes()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "the image is larger than the limit");
        }
        // 상한은 한 번 보낼 때의 장수다. 대화 전체를 세면 이미 보낸 사진 때문에 보관 기간 내내 더
        // 올리지 못한다.
        if (attachments.countByConversationIdAndMessageIdIsNullAndDeletedAtIsNull(conversationId)
                >= properties.maxFiles()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "too many images are waiting to be sent");
        }

        Instant now = Instant.now();
        ChatAttachment attachment = attachments.save(ChatAttachment.of(
                conversationId,
                user.id(),
                displayName(originalName),
                normalizedType,
                byteSize,
                now.plus(Duration.ofDays(properties.retentionDays()))));
        attachment.nameStoredFile(AttachmentStore.storedName(attachment.id(), extension));

        try (InputStream in = body.getInputStream()) {
            store.save(conversationId, attachment.id(), extension, in);
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "could not read the uploaded image", ex);
        }
        return attachment;
    }

    /** 사진 한 장의 본문을 연다. 지워졌으면 있었다는 것만 알린다. */
    public AttachmentContent read(CurrentUser user, Long conversationId, Long attachmentId) {
        ChatAttachment attachment = requireOwnAttachment(user, conversationId, attachmentId);
        if (!attachment.isVisible()) {
            throw new ApiException(ErrorCode.ATTACHMENT_GONE, "this attachment is no longer kept");
        }
        return new AttachmentContent(
                attachment.contentType(), attachment.byteSize(), store.open(attachment));
    }

    /** 보관 기간을 기다리지 않고 지운다. 이미 지워졌으면 아무것도 하지 않는다. 행은 남긴다. */
    @Transactional
    public void deleteByUser(CurrentUser user, Long conversationId, Long attachmentId) {
        ChatAttachment attachment = requireOwnAttachment(user, conversationId, attachmentId);
        if (!attachment.isVisible()) {
            return;
        }
        store.delete(attachment);
        attachment.markDeleted(Instant.now());
    }

    /**
     * 이 첨부들을 이 대화의 메시지에 묶을 수 있는지 메시지를 저장하기 전에 판정한다.
     *
     * <p>어느 까닭으로 거절했는지 갈라 알리지 않는다. 남의 번호와 없는 번호가 같은 응답이어야 번호를
     * 훑어 남의 것을 알아낼 수 없다.
     *
     * @return 판정을 통과한 첨부들. 요청한 번호 순서다. 목록이 비었으면 빈 목록이다
     */
    public List<ChatAttachment> requireAttachable(Long conversationId, List<Long> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return List.of();
        }
        if (attachmentIds.size() > properties.maxFiles()
                || new HashSet<>(attachmentIds).size() != attachmentIds.size()) {
            throw notAttachable();
        }
        List<ChatAttachment> found =
                attachments.findByConversationIdAndIdIn(conversationId, attachmentIds);
        boolean allFree = found.size() == attachmentIds.size()
                && found.stream().allMatch(it -> it.messageId() == null && it.isVisible());
        if (!allFree) {
            throw notAttachable();
        }
        return found.stream()
                .sorted(Comparator.comparingInt(it -> attachmentIds.indexOf(it.id())))
                .toList();
    }

    /**
     * 저장한 메시지에 첨부들을 묶는다.
     *
     * <p>조건부 갱신 하나로 한다. 갱신한 행 수가 목록 길이와 다르면 그 사이에 다른 요청이 먼저 묶었거나
     * 지워진 것이므로 거절한다.
     */
    @Transactional
    public void attach(Long messageId, Long conversationId, List<Long> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        int updated = attachments.attachToMessage(messageId, conversationId, attachmentIds);
        if (updated != attachmentIds.size()) {
            throw notAttachable();
        }
    }

    /**
     * Hermes 에 보낼 입력을 만든다. 사진이 놓인 자리와 파일 이름을 사용자가 쓴 글 앞에 붙인다.
     *
     * <p>{@code /v1/runs} 가 이미지 항목을 받지 않아 사진을 본문에 싣지 못한다. 대신 에이전트가 파일로
     * 읽게 자리를 알린다. 근거는 ADR-020 에 있다. 경로는 Hermes 컨테이너에서 보이는 {@code agentRoot}
     * 로 적는다. 파일은 디스크 이름으로만 찾을 수 있고, 올릴 때의 이름은 알아보라고 괄호로만 붙인다.
     *
     * <p>사진이 없으면 한 글자도 붙이지 않는다. 붙이면 그만큼이 매 실행에 실린다. 저장하는 메시지 본문에는
     * 이것을 쓰지 않는다.
     */
    public String agentInput(Long conversationId, List<ChatAttachment> attached, String text) {
        if (attached == null || attached.isEmpty()) {
            return text;
        }
        String directory = stripTrailingSlash(properties.agentRoot()) + "/" + conversationId;
        String files = attached.stream()
                .map(it -> "- " + it.storedName() + " (올린 이름: " + it.originalName() + ")")
                .collect(Collectors.joining("\n"));
        return "[이번 메시지에 올린 사진]\n"
                + directory + "\n"
                + files + "\n"
                + "\n"
                + "이미지는 read_file 로 읽지 말고 vision_analyze 로 본다.\n"
                + "\n"
                + text;
    }

    /** 그 대화의 첨부를 번호 순으로 돌려준다. 지난 대화에 자리를 남기려고 지워진 것도 담는다. */
    public List<ChatAttachment> allOf(Long conversationId) {
        return attachments.findByConversationIdOrderByIdAsc(conversationId);
    }

    private ChatAttachment requireOwnAttachment(
            CurrentUser user, Long conversationId, Long attachmentId) {
        access.requireOwn(user, conversationId);
        return attachments
                .findByIdAndConversationId(attachmentId, conversationId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.CONVERSATION_NOT_FOUND, "this conversation does not exist"));
    }

    private static String stripTrailingSlash(String path) {
        String stripped = path.strip();
        while (stripped.length() > 1 && stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    private static ApiException notAttachable() {
        return new ApiException(
                ErrorCode.VALIDATION_FAILED, "these attachments cannot be sent with this message");
    }

    /** {@code image/JPEG; charset=...} 같은 값을 비교할 수 있게 매개변수를 떼고 소문자로 맞춘다. */
    private static String normalize(String contentType) {
        if (contentType == null) {
            return null;
        }
        int separator = contentType.indexOf(';');
        String bare = separator < 0 ? contentType : contentType.substring(0, separator);
        return bare.strip().toLowerCase(Locale.ROOT);
    }

    private static String displayName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return FALLBACK_NAME;
        }
        String name = originalName.strip();
        return name.length() <= ORIGINAL_NAME_LIMIT ? name : name.substring(0, ORIGINAL_NAME_LIMIT);
    }
}
