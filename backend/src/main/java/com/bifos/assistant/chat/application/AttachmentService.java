package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.infra.AttachmentProperties;
import com.bifos.assistant.chat.infra.AttachmentStore;
import com.bifos.assistant.chat.infra.ChatAttachmentRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final Clock clock;

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
        access.requireOwnForUpload(user, conversationId);
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
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "too many images are waiting to be sent");
        }

        byte[] jpeg = null;
        if ("image/jpeg".equals(normalizedType)) {
            try (InputStream in = body.getInputStream()) {
                jpeg = in.readNBytes(Math.toIntExact(properties.maxBytes() + 1));
                if (jpeg.length > properties.maxBytes()) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "the image is larger than the limit");
                }
                jpeg = MpoJpegNormalizer.normalize(jpeg);
                byteSize = jpeg.length;
            } catch (IOException ex) {
                throw new ApiException(ErrorCode.INTERNAL_ERROR, "could not read the uploaded image", ex);
            }
        }
        Instant now = clock.instant();
        ChatAttachment attachment = attachments.save(ChatAttachment.of(
                conversationId,
                user.id(),
                displayName(originalName),
                normalizedType,
                byteSize,
                now.plus(Duration.ofDays(properties.retentionDays())),
                now));
        attachment.nameStoredFile(AttachmentStore.storedName(attachment.id(), extension));

        try (InputStream in = jpeg == null ? body.getInputStream() : new ByteArrayInputStream(jpeg)) {
            store.save(attachment, in);
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
        return new AttachmentContent(attachment.contentType(), attachment.byteSize(), store.open(attachment));
    }

    /** 보관 기간을 기다리지 않고 지운다. 이미 지워졌으면 아무것도 하지 않는다. 행은 남긴다. */
    @Transactional
    public void deleteByUser(CurrentUser user, Long conversationId, Long attachmentId) {
        ChatAttachment attachment = requireOwnAttachment(user, conversationId, attachmentId);
        if (!attachment.isVisible()) {
            return;
        }
        store.delete(attachment);
        attachment.markDeleted(clock.instant());
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
        List<ChatAttachment> found = attachments.findByConversationIdAndIdIn(conversationId, attachmentIds);
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
     * <p>요청한 배열 순서로 0부터 자리를 준다. 각 조건부 갱신이 한 행을 바꾸지 못하면 그 사이에 다른 요청이
     * 먼저 묶었거나 지워진 것이므로 거절하고 트랜잭션 전체를 되돌린다.
     */
    @Transactional
    public void attach(Long messageId, Long conversationId, List<Long> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        for (int position = 0; position < attachmentIds.size(); position++) {
            int updated = attachments.attachToMessageAtPosition(
                    messageId, conversationId, attachmentIds.get(position), position);
            if (updated != 1) {
                throw notAttachable();
            }
        }
    }

    /**
     * Hermes 에 보낼 입력을 만든다. 사진이 놓인 자리와 파일 이름을 사용자가 쓴 글 앞에 붙인다.
     *
     * <p>{@code /v1/runs} 가 이미지 항목을 받지 않아 사진을 본문에 싣지 못한다. 대신 에이전트가 파일로
     * 읽게 자리를 알린다. 근거는 ADR-020 에 있다. 경로는 Hermes 컨테이너에서 보이는 {@code agentRoot}
     * 로 적는다. 파일은 디스크 이름으로만 찾을 수 있고, 올릴 때의 이름은 알아보라고 괄호로만 붙인다.
     *
     * <p>사진마다 이 대화에서 몇 번째 사진인지 붙인다. 디스크 이름은 첨부 번호라 여러 대화에 걸쳐 커지고,
     * 에이전트가 그 이름으로 사진을 가리키면 사용자는 화면에서 어느 사진인지 찾지 못한다. 순번은 메시지와
     * 메시지 안의 저장된 순서로 세므로 화면에 보이는 순서와 같고, 다시 생성해도 바뀌지 않는다.
     *
     * <p>사진이 없으면 한 글자도 붙이지 않는다. 붙이면 그만큼이 매 실행에 실린다. 저장하는 메시지 본문에는
     * 이것을 쓰지 않는다.
     */
    public String agentInput(Long conversationId, List<ChatAttachment> attached, String text) {
        if (attached == null || attached.isEmpty()) {
            return text;
        }
        Long ownerUserId = attached.getFirst().uploadedByUserId();
        boolean sameOwnerAndConversation = attached.stream()
                .allMatch(attachment -> ownerUserId.equals(attachment.uploadedByUserId())
                        && conversationId.equals(attachment.conversationId()));
        if (!sameOwnerAndConversation) {
            throw notAttachable();
        }
        String directory = stripTrailingSlash(properties.agentRoot()) + "/users/"
                + AttachmentStore.userDirectoryKey(ownerUserId) + "/" + conversationId;
        Map<Long, Integer> order = orderInConversation(conversationId);
        String files = attached.stream()
                .map(it ->
                        "- " + order.get(it.id()) + "번째 사진: " + it.storedName() + " (올린 이름: " + it.originalName() + ")")
                .collect(Collectors.joining("\n"));
        return "[이번 메시지에 올린 사진]\n"
                + directory + "\n"
                + files + "\n"
                + "\n"
                + "이미지는 read_file 로 읽지 말고 vision_analyze 로 본다.\n"
                + "사용자에게 사진을 가리킬 때는 파일 이름 대신 몇 번째 사진인지로 적는다.\n"
                + "\n"
                + text;
    }

    /**
     * 메시지에 묶인 첨부를 화면에 보이는 차례로 세어 첨부 번호마다 1부터 매긴 순번을 돌려준다.
     *
     * <p>화면은 메시지 순서로 묶고 한 메시지 안에서는 저장한 순서로 보인다. 첨부 번호로만 세면 한 창에서
     * 올려 둔 사진을 다른 창의 사진보다 늦게 보냈을 때 순번이 어긋난다.
     */
    private Map<Long, Integer> orderInConversation(Long conversationId) {
        Map<Long, Integer> order = new HashMap<>();
        attachments.findByConversationIdOrderByMessageIdAscPositionAsc(conversationId).stream()
                .filter(attachment -> attachment.messageId() != null)
                .forEach(attachment -> order.put(attachment.id(), order.size() + 1));
        return order;
    }

    /** 그 대화의 첨부를 메시지와 메시지 안의 자리 순으로 돌려준다. 지난 대화에 자리를 남기려고 지워진 것도 담는다. */
    public List<ChatAttachment> allOf(Long conversationId) {
        return attachments.findByConversationIdOrderByMessageIdAscPositionAsc(conversationId);
    }

    private ChatAttachment requireOwnAttachment(CurrentUser user, Long conversationId, Long attachmentId) {
        access.requireOwn(user, conversationId);
        return attachments
                .findByIdAndConversationId(attachmentId, conversationId)
                .orElseThrow(
                        () -> new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "this conversation does not exist"));
    }

    private static String stripTrailingSlash(String path) {
        String stripped = path.strip();
        while (stripped.length() > 1 && stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    private static ApiException notAttachable() {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "these attachments cannot be sent with this message");
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

    /**
     * 화면과 Hermes 입력에 보일 이름을 만든다.
     *
     * <p>이 이름은 Hermes 입력에 그대로 실린다. 줄바꿈이 섞이면 올린 이름으로 입력에 줄을 끼워 넣을 수
     * 있어 제어 문자와 줄 구분 문자를 모두 공백으로 바꾼다.
     */
    private static String displayName(String originalName) {
        if (originalName == null) {
            return FALLBACK_NAME;
        }
        String name = originalName.replaceAll("[\\p{Cc}\\u2028\\u2029]", " ").strip();
        if (name.isEmpty()) {
            return FALLBACK_NAME;
        }
        return name.length() <= ORIGINAL_NAME_LIMIT ? name : name.substring(0, ORIGINAL_NAME_LIMIT);
    }
}
