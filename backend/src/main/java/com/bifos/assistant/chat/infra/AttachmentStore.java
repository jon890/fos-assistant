package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.hermes.SandboxAttachmentDirectory;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 첨부 파일을 디스크에 두고 읽고 지운다.
 *
 * <p>파일 경로를 만드는 규칙이 이 클래스 하나에만 있다. 규칙이 흩어지면 지우는 쪽이 놓친다. 사용자 디렉터리 키와
 * Hermes 를 부르기 전에 그 디렉터리를 만드는 일만 {@link SandboxAttachmentDirectory} 가 맡는다. 경로는
 * {@code {root}/users/{사용자 디렉터리 키}/{대화 번호}/{첨부 번호}.{확장자}} 이고, 확장자는 올릴 때의 파일 이름이 아니라
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
    private final boolean legacyWriteEnabled;

    public AttachmentStore(AttachmentProperties properties) {
        this.root = Path.of(properties.root()).toAbsolutePath().normalize();
        this.legacyWriteEnabled = properties.legacyWriteEnabled();
    }

    /** 받는 형식이면 그 확장자를, 아니면 빈 값을 돌려준다. */
    public static Optional<String> extensionFor(String contentType) {
        return Optional.ofNullable(contentType).map(EXTENSIONS::get);
    }

    /** 디스크에 둘 이름이다. */
    public static String storedName(Long attachmentId, String extension) {
        return attachmentId + "." + extension;
    }

    /** 실행 주인 {@code u<사용자 번호>} 의 디렉터리 키다. Hermes 를 부르기 전에 만드는 디렉터리와 같은 규칙을 쓴다. */
    public static String userDirectoryKey(Long userId) {
        requirePositive(userId);
        return SandboxAttachmentDirectory.key("u" + userId);
    }

    public synchronized void save(ChatAttachment attachment, InputStream body) {
        Path target = privatePath(attachment);
        Path legacy = legacyWriteEnabled ? legacyPath(attachment) : null;
        saveNewFile(target, body);
        if (!legacyWriteEnabled) {
            return;
        }
        boolean legacyCreated = false;
        try (InputStream copy = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
            saveNewFile(legacy, copy);
            legacyCreated = true;
        } catch (IOException | RuntimeException ex) {
            deleteQuietly(target);
            if (legacyCreated) {
                deleteQuietly(legacy);
            }
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "could not store the legacy attachment copy", ex);
        }
    }

    private static void saveNewFile(Path target, InputStream body) {
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
    public synchronized InputStream open(ChatAttachment attachment) {
        try {
            prepare(attachment);
            return Files.newInputStream(privatePath(attachment), LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException ex) {
            throw new ApiException(ErrorCode.ATTACHMENT_GONE, "this attachment is no longer kept", ex);
        } catch (IOException ex) {
            throw new UncheckedIOException("could not open attachment " + attachment.id(), ex);
        }
    }

    /** 파일을 지운다. 이미 없으면 그대로 끝난다. */
    public synchronized void delete(ChatAttachment attachment) {
        try {
            // 되돌리기용 옛 사본도 함께 지워야 다음 읽기에서 삭제한 사진이 복구되지 않는다.
            Files.deleteIfExists(legacyPath(attachment));
            Files.deleteIfExists(privatePath(attachment));
        } catch (IOException ex) {
            throw new UncheckedIOException("could not delete attachment " + attachment.id(), ex);
        }
    }

    /**
     * 옛 사진을 사용자 디렉터리에 복사한다. 첫 배포에서는 되돌리기용 원본을 남긴다(ADR-090).
     *
     * <p>화면 읽기와 Hermes 입력 생성도 이 메서드를 부르므로 기동 작업과 겹쳐도 부분 파일을 읽지 않는다.
     * 같은 프로세스의 저장과 삭제도 같은 잠금을 쓴다. 여러 Control Plane 을 동시에 띄우지 않는다.
     */
    public synchronized void prepare(ChatAttachment attachment) {
        Path source = legacyPath(attachment);
        Path target = privatePath(attachment);
        Path temporary = null;
        try {
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("legacy attachment is not a regular file");
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.mismatch(source, target) != -1) {
                    throw new IOException("private and legacy attachment copies differ");
                }
                return;
            }
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), ".attachment-", ".tmp");
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("could not copy legacy attachment " + attachment.id(), ex);
        } finally {
            if (temporary != null) {
                deleteQuietly(temporary);
            }
        }
    }

    private Path privatePath(ChatAttachment attachment) {
        return checkedPath(root.resolve("users")
                .resolve(userDirectoryKey(attachment.uploadedByUserId()))
                .resolve(conversationDirectory(attachment))
                .resolve(checkedStoredName(attachment)));
    }

    private Path legacyPath(ChatAttachment attachment) {
        return checkedPath(root.resolve(conversationDirectory(attachment)).resolve(checkedStoredName(attachment)));
    }

    private Path checkedPath(Path path) {
        Path current = root;
        if (Files.isSymbolicLink(current)) {
            throw new IllegalArgumentException("attachment root is a symbolic link");
        }
        for (Path part : root.relativize(path)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("attachment path contains a symbolic link");
            }
        }
        return path;
    }

    private static String conversationDirectory(ChatAttachment attachment) {
        requirePositive(attachment.conversationId());
        return attachment.conversationId().toString();
    }

    private static String checkedStoredName(ChatAttachment attachment) {
        requirePositive(attachment.id());
        String extension = extensionFor(attachment.contentType())
                .orElseThrow(() -> new IllegalArgumentException("unsupported attachment type"));
        String expected = storedName(attachment.id(), extension);
        if (!expected.equals(attachment.storedName())) {
            throw new IllegalArgumentException("unexpected stored attachment name");
        }
        return expected;
    }

    private static void requirePositive(Long id) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("attachment identifiers must be positive");
        }
    }

    private static void deleteQuietly(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException ignored) {
            // 쓰다 남은 조각을 지우지 못해도 원래 실패를 그대로 올린다.
        }
    }
}
