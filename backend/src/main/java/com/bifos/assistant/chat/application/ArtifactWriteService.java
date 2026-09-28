package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ArtifactStore;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 대화 주인을 확인한 뒤 HTML 또는 CSS 본문을 결과물 폴더에 쓴다. */
@Service
@RequiredArgsConstructor
public class ArtifactWriteService {

    static final int MAX_CONTENT_BYTES = 5 * 1024 * 1024;

    private final ConversationAccess conversations;
    private final ArtifactStore store;

    /**
     * 요청한 대화에 본문 파일 하나를 원자적으로 쓴다.
     *
     * <p>소유권 확인은 경로와 본문을 보기 전에 한다. 도구 호출자가 다른 대화의 존재나 디스크 상태를 알아내지
     * 못하게 하기 위해서다.
     */
    public ArtifactWriteResult write(CurrentUser user, ArtifactWriteRequest request) {
        Conversation conversation = conversations.requireOwn(user, request.conversationId());
        byte[] content = contentOf(request);
        requireWritableExtension(request.path());
        long byteSize = store.write(conversation.id(), request.path(), content);
        return new ArtifactWriteResult(request.path(), byteSize);
    }

    private static byte[] contentOf(ArtifactWriteRequest request) {
        if (request.content() == null || request.sourceUrl() != null) {
            throw validation("exactly one inline content request is required");
        }
        byte[] content = request.content().getBytes(StandardCharsets.UTF_8);
        if (content.length > MAX_CONTENT_BYTES) {
            throw validation("artifact content must not exceed 5 MiB");
        }
        return content;
    }

    private static void requireWritableExtension(String path) {
        if (path == null) {
            throw validation("artifact path is required");
        }
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        String extension = dot <= slash ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!extension.equals("html") && !extension.equals("css")) {
            throw validation("inline artifact content must be HTML or CSS");
        }
    }

    private static ApiException validation(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }
}
