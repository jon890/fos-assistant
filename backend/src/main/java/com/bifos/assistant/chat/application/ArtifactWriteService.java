package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.infra.ArtifactSourceFetcher;
import com.bifos.assistant.chat.infra.ArtifactStore;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** 대화 주인을 확인한 뒤 HTML 또는 CSS 본문을 결과물 폴더에 쓴다. */
@Service
public class ArtifactWriteService {

    static final int MAX_CONTENT_BYTES = 5 * 1024 * 1024;

    private final ConversationAccess conversations;
    private final ArtifactStore store;
    private final ArtifactSourceFetcher sourceFetcher;

    public ArtifactWriteService(
            ConversationAccess conversations, ArtifactStore store, ArtifactSourceFetcher sourceFetcher) {
        this.conversations = conversations;
        this.store = store;
        this.sourceFetcher = sourceFetcher;
    }

    /**
     * 요청한 대화에 본문 파일 하나를 원자적으로 쓴다.
     *
     * <p>소유권 확인은 경로와 본문을 보기 전에 한다. 도구 호출자가 다른 대화의 존재나 디스크 상태를 알아내지
     * 못하게 하기 위해서다.
     */
    public ArtifactWriteResult write(CurrentUser user, ArtifactWriteRequest request) {
        Conversation conversation = conversations.requireOwn(user, request.conversationId());
        byte[] content = contentOf(request);
        long byteSize = store.write(conversation.id(), request.path(), content);
        return new ArtifactWriteResult(request.path(), byteSize);
    }

    private byte[] contentOf(ArtifactWriteRequest request) {
        if ((request.content() == null) == (request.sourceUrl() == null)) {
            throw validation("exactly one artifact content request is required");
        }
        if (request.sourceUrl() != null) {
            ArtifactStore.requireWritablePath(request.path());
            String contentType = imageContentType(request.path());
            try {
                return sourceFetcher.fetch(URI.create(request.sourceUrl()), contentType);
            } catch (IllegalArgumentException ex) {
                throw validation("artifact source URL is invalid");
            }
        }
        requireInlineExtension(request.path());
        byte[] content = request.content().getBytes(StandardCharsets.UTF_8);
        if (content.length > MAX_CONTENT_BYTES) {
            throw validation("artifact content must not exceed 5 MiB");
        }
        return content;
    }

    private static void requireInlineExtension(String path) {
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

    private static String imageContentType(String path) {
        if (path == null) {
            throw validation("artifact path is required");
        }
        String contentType =
                ArtifactStore.contentTypeOf(path).orElseThrow(() -> validation("URL artifact path must be an image"));
        String mediaType = contentType.split(";", 2)[0];
        if (!mediaType.startsWith("image/")) {
            throw validation("URL artifact path must be an image");
        }
        return mediaType;
    }

    private static ApiException validation(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }
}
