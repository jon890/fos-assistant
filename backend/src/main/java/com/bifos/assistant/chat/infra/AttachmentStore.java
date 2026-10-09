package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.hermes.SandboxAttachmentDirectory;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
 *
 * <p>같은 폴더에 에이전트에게 보일 줄인 사본 {@code {첨부 번호}.small.jpg} 를 둔다. 사본은 임시 파일
 * {@code {첨부 번호}.small.jpg.tmp} 에 쓴 뒤 옮겨, 실행 공간이 반쯤 쓴 사본을 읽지 않게 한다. 원본을 지울 때
 * 사본과 남은 임시 파일을 함께 지운다. 근거는 ADR-20261009 / native-image-input 에 있다.
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

    /** 원본 옆에 둘 줄인 사본의 이름이다. */
    public static String smallName(Long attachmentId) {
        return attachmentId + ".small.jpg";
    }

    /** 실행 주인 {@code u<사용자 번호>} 의 디렉터리 키다. Hermes 를 부르기 전에 만드는 디렉터리와 같은 규칙을 쓴다. */
    public static String userDirectoryKey(Long userId) {
        requirePositive(userId);
        return SandboxAttachmentDirectory.key("u" + userId);
    }

    public synchronized void save(ChatAttachment attachment, InputStream body) {
        saveNewFile(privatePath(attachment), body);
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
            return Files.newInputStream(privatePath(attachment), LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException ex) {
            throw new ApiException(ErrorCode.ATTACHMENT_GONE, "this attachment is no longer kept", ex);
        } catch (IOException ex) {
            throw new UncheckedIOException("could not open attachment " + attachment.id(), ex);
        }
    }

    /**
     * 줄인 사본을 원본 옆에 쓴다. 원본이 없거나 사본이 이미 있으면 아무것도 쓰지 않는다.
     *
     * <p>사본은 lock 밖에서 디코딩해 만들므로 그 사이 원본이 지워졌을 수 있다. 원본 없이 사본을 쓰면 지운 시각이
     * 적힌 행이라 다시 지울 경로가 없어 사본이 디스크에 남는다. 그래서 같은 lock 안에서 원본을 먼저 본다.
     */
    public synchronized void saveSmall(ChatAttachment attachment, byte[] jpeg) {
        Path original = privatePath(attachment);
        Path target = smallPath(attachment);
        Path temporary = temporarySmallPath(attachment);
        if (!Files.exists(original, LinkOption.NOFOLLOW_LINKS) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            // 앞선 쓰기가 남긴 임시 파일이 있으면 지우고 새로 만든다.
            Files.deleteIfExists(temporary);
            Files.write(temporary, jpeg, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            deleteQuietly(temporary);
            throw new UncheckedIOException("could not store the small copy of attachment " + attachment.id(), ex);
        }
    }

    /** 줄인 사본이 있는지 본다. */
    public synchronized boolean hasSmall(ChatAttachment attachment) {
        return Files.isRegularFile(smallPath(attachment), LinkOption.NOFOLLOW_LINKS);
    }

    /** 줄인 사본을 읽는다. 없으면 지워진 첨부로 알린다. */
    public synchronized byte[] readSmall(ChatAttachment attachment) {
        try (InputStream in = Files.newInputStream(smallPath(attachment), LinkOption.NOFOLLOW_LINKS)) {
            return in.readAllBytes();
        } catch (NoSuchFileException ex) {
            throw new ApiException(ErrorCode.ATTACHMENT_GONE, "this attachment is no longer kept", ex);
        } catch (IOException ex) {
            throw new UncheckedIOException("could not read the small copy of attachment " + attachment.id(), ex);
        }
    }

    /** 원본과 줄인 사본, 쓰다 남은 임시 파일을 지운다. 이미 없으면 그대로 끝난다. */
    public synchronized void delete(ChatAttachment attachment) {
        // 경로 검사를 먼저 모두 지나야 지우기 시작한다. 하나만 거절되면 원본만 지워진 채 남는다.
        Path original = privatePath(attachment);
        Path small = smallPath(attachment);
        Path temporary = temporarySmallPath(attachment);
        try {
            Files.deleteIfExists(original);
            Files.deleteIfExists(small);
            Files.deleteIfExists(temporary);
        } catch (IOException ex) {
            throw new UncheckedIOException("could not delete attachment " + attachment.id(), ex);
        }
    }

    private Path privatePath(ChatAttachment attachment) {
        return checkedPath(root.resolve("users")
                .resolve(userDirectoryKey(attachment.uploadedByUserId()))
                .resolve(conversationDirectory(attachment))
                .resolve(checkedStoredName(attachment)));
    }

    private Path smallPath(ChatAttachment attachment) {
        return checkedPath(privatePath(attachment).resolveSibling(smallName(attachment.id())));
    }

    private Path temporarySmallPath(ChatAttachment attachment) {
        return checkedPath(privatePath(attachment).resolveSibling(smallName(attachment.id()) + ".tmp"));
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
