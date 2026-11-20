package com.bifos.assistant.chat.artifact.infra;

import static com.bifos.assistant.chat.artifact.infra.ArtifactPathPolicy.requireOrdinaryTarget;
import static com.bifos.assistant.chat.artifact.infra.ArtifactPathPolicy.requireOrdinaryTargetInDirectory;
import static com.bifos.assistant.chat.artifact.infra.ArtifactPathPolicy.storeFailure;
import static com.bifos.assistant.chat.artifact.infra.ArtifactPathPolicy.verifyParent;

import com.bifos.assistant.shared.error.ApiException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * 검증한 결과물 경로에서 임시 파일을 완성하고 원자적으로 교체한다. 대화 잠금은 호출자가 잡는다.
 *
 * <p>{@link SecureDirectoryStream} 의 핸들에서만 쓴다. 핸들로 임시 파일을 만들고 교체해, 확인과 열기 사이에 중간 디렉터리가
 * 링크로 바뀌어도 다른 디렉터리에 쓰지 않는다(#359). 핸들을 쓸 수 없는 운영체제(musl)에서는 결과물 저장을 끈다(fail-closed).
 * 운영 이미지는 glibc 다.
 */
@Slf4j(topic = "com.bifos.assistant.chat.artifact.infra.ArtifactStore")
final class ArtifactFileWriter {

    private final ArtifactPathPolicy paths;


    ArtifactFileWriter(ArtifactPathPolicy paths) {
        this.paths = paths;
    }


    long writeLocked(Long conversationId, String relativePath, byte[] content) {
        Path target = paths.resolveForWrite(conversationId, relativePath);
        Path parent = target.getParent();
        try {
            Path folder = paths.checkedConversationFolder(conversationId);
            verifyParent(parent, folder);
            requireOrdinaryTarget(target);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
                if (!(stream instanceof SecureDirectoryStream<Path> secure)) {
                    throw new IOException("secure artifact write is unavailable");
                }
                writeWithSecureDirectory(secure, target.getFileName(), content);
            }
            return content.length;
        } catch (ApiException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw storeFailure(ex);
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
