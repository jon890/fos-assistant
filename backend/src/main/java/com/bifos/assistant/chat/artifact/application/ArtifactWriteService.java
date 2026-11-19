package com.bifos.assistant.chat.artifact.application;

import com.bifos.assistant.chat.artifact.application.dto.ArtifactWriteRequest;
import com.bifos.assistant.chat.artifact.application.dto.ArtifactWriteResult;
import com.bifos.assistant.chat.artifact.domain.ArtifactReferences;
import com.bifos.assistant.chat.artifact.domain.VaultLabel;
import com.bifos.assistant.chat.artifact.infra.ArtifactVaultMeta;
import com.bifos.assistant.chat.conversation.application.ConversationAccess;
import com.bifos.assistant.chat.conversation.domain.Conversation;
import com.bifos.assistant.chat.artifact.infra.ArtifactSourceFetcher;
import com.bifos.assistant.chat.artifact.infra.ArtifactStore;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 대화 주인을 확인한 뒤 HTML 또는 CSS 본문을 결과물 폴더에 쓴다. */
@Service
@Slf4j
public class ArtifactWriteService {

    static final int MAX_CONTENT_BYTES = 5 * 1024 * 1024;
    private static final String HTML = "html";

    private final ConversationAccess conversations;
    private final ArtifactStore store;
    private final ArtifactSourceFetcher sourceFetcher;
    private final ArtifactVaultMeta metas;
    private final ChatArtifactWriter rows;


    public ArtifactWriteService(
        ConversationAccess conversations, ArtifactStore store, ArtifactSourceFetcher sourceFetcher,
        ArtifactVaultMeta metas, ChatArtifactWriter rows) {
        this.conversations = conversations;
        this.store = store;
        this.sourceFetcher = sourceFetcher;
        this.metas = metas;
        this.rows = rows;
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
        rememberTitle(user, conversation.id(), request, byteSize);
        return new ArtifactWriteResult(request.path(), byteSize);
    }


    /**
     * HTML 에 받은 제목과 설명을 그 경로의 답에 묶이지 않은 결과물 행에 암호화해 둔다(ADR-20261119 / file-space-put).
     * 보관할 때 보관본의 제목으로 옮겨진다.
     *
     * <p>파일은 이미 썼다. 암호화를 쓸 수 없거나 저장이 실패하면 제목만 버리고 쓰기는 성공으로 둔다.
     */
    private void rememberTitle(CurrentUser user, Long conversationId, ArtifactWriteRequest request, long byteSize) {
        if (!HTML.equals(extensionOf(request.path()))) {
            return;
        }
        Optional<VaultLabel> label = VaultLabel.of(request.title(), request.description());
        if (label.isEmpty()) {
            return;
        }
        String path = ArtifactReferences.normalize(request.path());
        try {
            metas.seal(user.id(), conversationId, path, label.get()).ifPresent(
                sealed -> rows.putWrittenTitle(conversationId, path, byteSize, sealed.content(), sealed.keyId()));
        } catch (RuntimeException ex) {
            log.warn(
                "could not keep the title of a written artifact userId={} error={}", user.id(),
                ex.getClass().getSimpleName());
        }
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
        String extension = extensionOf(path);
        if (!extension.equals(HTML) && !extension.equals("css")) {
            throw validation("inline artifact content must be HTML or CSS");
        }
    }


    private static String extensionOf(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot <= slash ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }


    private static String imageContentType(String path) {
        if (path == null) {
            throw validation("artifact path is required");
        }
        String contentType = ArtifactStore.contentTypeOf(path).orElseThrow(() -> validation("URL artifact path must be an image"));
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
