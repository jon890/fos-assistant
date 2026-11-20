package com.bifos.assistant.chat.artifact.infra;

import static com.bifos.assistant.chat.artifact.infra.ArtifactPathPolicy.requireOrdinaryTargetInDirectory;
import static com.bifos.assistant.chat.artifact.infra.ArtifactPathPolicy.storeFailure;

import com.bifos.assistant.shared.error.ApiException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * 검증한 결과물 경로에서 임시 파일을 완성하고 원자적으로 교체한다. 대화 잠금은 호출자가 잡는다.
 *
 * <p>대화 폴더부터 중간 조각마다 {@link SecureDirectoryStream} 의 핸들에서 다음 조각을 열어({@code openat}+{@code O_NOFOLLOW})
 * 마지막 핸들에 쓴다. 핸들로 따라오므로 확인과 쓰기 사이에 중간 디렉터리가 링크로 바뀌어도 다른 디렉터리에 쓰지 않는다(#359).
 * 중간 디렉터리 만들기는 경로로 하되(검증은 {@link ArtifactPathPolicy#resolveForWrite} 가 한다), 쓰기는 핸들로만 한다.
 *
 * <p>핸들을 쓸 수 없는 운영체제(musl)에서는 결과물 저장을 끈다(fail-closed). 운영 이미지는 glibc 다.
 */
@Slf4j(topic = "com.bifos.assistant.chat.artifact.infra.ArtifactStore")
final class ArtifactFileWriter {

    private final ArtifactPathPolicy paths;


    ArtifactFileWriter(ArtifactPathPolicy paths) {
        this.paths = paths;
    }


    long writeLocked(Long conversationId, String relativePath, byte[] content) {
        try {
            // 경로를 먼저 검증한다. 잘못된 경로면 대화 폴더를 만들기 전에 거절한다.
            List<String> parts = ArtifactPathPolicy.writableParts(relativePath);
            Path folder = paths.checkedConversationFolder(conversationId);
            // 중간 디렉터리를 만들고 경로 규칙을 검증한다. 실제 쓰기는 아래 핸들 경로가 한다.
            paths.resolveForWrite(conversationId, relativePath);
            SecureDirectoryStream<Path> directory = openConversationFolder(folder);
            try {
                for (int index = 0; index < parts.size() - 1; index++) {
                    SecureDirectoryStream<Path> next = openChildDirectory(directory, folder.getFileSystem().getPath(parts.get(index)));
                    directory.close();
                    if (next == null) {
                        throw new IOException("artifact parent is not a directory");
                    }
                    directory = next;
                }
                writeWithSecureDirectory(directory, folder.getFileSystem().getPath(parts.getLast()), content);
            } finally {
                directory.close();
            }
            return content.length;
        } catch (ApiException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw storeFailure(ex);
        }
    }


    /** 대화 폴더의 핸들을 연다. {@link SecureDirectoryStream} 이 아니면 결과물 저장을 끈다(fail-closed). */
    private static SecureDirectoryStream<Path> openConversationFolder(Path folder) throws IOException {
        DirectoryStream<Path> stream = Files.newDirectoryStream(folder);
        if (stream instanceof SecureDirectoryStream<Path> secure) {
            return secure;
        }
        stream.close();
        throw new IOException("secure artifact write is unavailable");
    }


    /** 핸들 안의 이름이 링크가 아닌 디렉터리면 링크를 따라가지 않고 열고, 아니면 {@code null} 이다. */
    private static SecureDirectoryStream<Path> openChildDirectory(SecureDirectoryStream<Path> directory, Path name)
        throws IOException {
        try {
            BasicFileAttributes attributes = directory.getFileAttributeView(
                name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                .readAttributes();
            return attributes.isDirectory() && !attributes.isSymbolicLink()
                ? directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)
                : null;
        } catch (NoSuchFileException | NotDirectoryException ex) {
            return null;
        }
    }


    private static void writeWithSecureDirectory(SecureDirectoryStream<Path> directory, Path targetName, byte[] content)
        throws IOException {
        Path temporaryName = Path.of(".artifact-" + UUID.randomUUID() + ".tmp");
        boolean temporaryCreated = false;
        try {
            Set<OpenOption> options = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try (SeekableByteChannel channel = directory.newByteChannel(temporaryName, options)) {
                temporaryCreated = true;
                writeFully(channel, content);
            }
            requireOrdinaryTargetInDirectory(directory, targetName);
            directory.move(temporaryName, directory, targetName);
            temporaryCreated = false;
        } finally {
            if (temporaryCreated) {
                deleteSecurely(directory, temporaryName);
            }
        }
    }


    private static void writeFully(SeekableByteChannel channel, byte[] content) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(content);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }


    private static void deleteSecurely(SecureDirectoryStream<Path> directory, Path temporary) {
        try {
            directory.deleteFile(temporary);
        } catch (IOException ignored) {
            // 저장 실패의 원인을 임시 파일 정리 실패로 바꾸지 않는다.
        }
    }
}
