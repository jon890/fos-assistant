package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.application.AttachmentProperties;
import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 첨부 파일을 디스크에 두고 읽고 지운다.
 *
 * <p>경로를 만드는 규칙이 이 클래스 하나에만 있다. 규칙이 흩어지면 지우는 쪽이 놓친다. 경로는
 * {@code {root}/{대화 번호}/{첨부 번호}.{확장자}} 이고, 확장자는 올릴 때의 파일 이름이 아니라
 * {@code content_type} 에서 만든다. 올린 이름이 경로를 벗어나게 만들 수 있기 때문이다.
 */
@Component
public class AttachmentStore {

    /** 받는 형식과 그 확장자. 이 밖의 형식은 받지 않는다. */
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/gif", "gif",
            "image/webp", "webp");

    private final Path root;

    public AttachmentStore(AttachmentProperties properties) {
        this.root = Path.of(properties.root()).toAbsolutePath().normalize();
    }

    /** 받는 형식이면 그 확장자를, 아니면 빈 값을 돌려준다. */
    public static Optional<String> extensionFor(String contentType) {
        return Optional.ofNullable(contentType).map(EXTENSIONS::get);
    }

    /** 디스크에 둘 이름이다. */
    public static String storedName(Long attachmentId, String extension) {
        return attachmentId + "." + extension;
    }

    public void save(Long conversationId, Long attachmentId, String extension, InputStream body) {
        Path target = resolve(conversationId, storedName(attachmentId, extension));
        try {
            Files.createDirectories(target.getParent());
            Files.copy(body, target);
        } catch (FileAlreadyExistsException ex) {
            // 이미 있던 파일은 이 요청이 만든 것이 아니므로 지우지 않는다.
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "could not store the attachment", ex);
        } catch (IOException ex) {
            deleteQuietly(target);
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "could not store the attachment", ex);
        }
    }

    /**
     * 파일을 연다. 행은 보이는데 파일이 없으면 지워진 첨부로 알린다.
     *
     * <p>정리 작업이 파일을 지운 뒤 행에 지운 시각을 적기 전에 읽으면 이렇게 된다.
     */
    public InputStream open(ChatAttachment attachment) {
        try {
            return Files.newInputStream(resolve(attachment.conversationId(), attachment.storedName()));
        } catch (NoSuchFileException ex) {
            throw new ApiException(ErrorCode.ATTACHMENT_GONE, "this attachment is no longer kept", ex);
        } catch (IOException ex) {
            throw new UncheckedIOException("could not open attachment " + attachment.id(), ex);
        }
    }

    /** 파일을 지운다. 이미 없으면 그대로 끝난다. */
    public void delete(ChatAttachment attachment) {
        try {
            Files.deleteIfExists(resolve(attachment.conversationId(), attachment.storedName()));
        } catch (IOException ex) {
            throw new UncheckedIOException("could not delete attachment " + attachment.id(), ex);
        }
    }

    private Path resolve(Long conversationId, String storedName) {
        Path path = root.resolve(String.valueOf(conversationId)).resolve(storedName).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("attachment path escapes the root");
        }
        return path;
    }

    private static void deleteQuietly(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException ignored) {
            // 쓰다 남은 조각을 지우지 못해도 원래 실패를 그대로 올린다.
        }
    }
}
