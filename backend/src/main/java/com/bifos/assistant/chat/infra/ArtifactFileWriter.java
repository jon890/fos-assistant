package com.bifos.assistant.chat.infra;

import static com.bifos.assistant.chat.infra.ArtifactPathPolicy.requireOrdinaryTarget;
import static com.bifos.assistant.chat.infra.ArtifactPathPolicy.requireOrdinaryTargetInDirectory;
import static com.bifos.assistant.chat.infra.ArtifactPathPolicy.storeFailure;
import static com.bifos.assistant.chat.infra.ArtifactPathPolicy.verifyParent;
import com.bifos.assistant.shared.error.ApiException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/** 검증한 결과물 경로에서 임시 파일을 완성하고 원자적으로 교체한다. 대화 잠금은 호출자가 잡는다. */
@Slf4j(topic = "com.bifos.assistant.chat.infra.ArtifactStore")
final class ArtifactFileWriter {

    private static final AtomicBoolean UNSUPPORTED_SECURE_DIRECTORY_WARNING_LOGGED = new AtomicBoolean();

    private final ArtifactPathPolicy paths;
    private final boolean forceAtomicMoveFallback;
    private final ArtifactAtomicMover atomicMover;

    ArtifactFileWriter(ArtifactPathPolicy paths, boolean forceAtomicMoveFallback, ArtifactAtomicMover atomicMover) {
        this.paths = paths;
        this.forceAtomicMoveFallback = forceAtomicMoveFallback;
        this.atomicMover = atomicMover;
    }

    long writeLocked(Long conversationId, String relativePath, byte[] content) {
        Path target = paths.resolveForWrite(conversationId, relativePath);
        Path parent = target.getParent();
        try {
            Path folder = paths.checkedConversationFolder(conversationId);
            verifyParent(parent, folder);
            requireOrdinaryTarget(target);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
                if (!forceAtomicMoveFallback && stream instanceof SecureDirectoryStream<Path> secure) {
                    writeWithSecureDirectory(secure, target.getFileName(), content);
                } else {
                    if (!forceAtomicMoveFallback) {
                        warnUnsupportedSecureDirectoryOnce();
                    } else {
                        log.warn("using forced checked atomic artifact replacement for verification");
                    }
                    writeWithAtomicMove(parent, target, folder, content, atomicMover);
                }
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
            Set<OpenOption> options =
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
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

    private static void writeWithAtomicMove(
            Path parent, Path target, Path folder, byte[] content, ArtifactAtomicMover atomicMover) throws IOException {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(parent, ".artifact-", ".tmp");
            try (SeekableByteChannel channel = Files.newByteChannel(
                    temporary, EnumSet.of(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))) {
                writeFully(channel, content);
            }
            verifyParent(parent, folder);
            requireOrdinaryTarget(target);
            atomicMover.move(temporary, target);
            temporary = null;
        } catch (AtomicMoveNotSupportedException ex) {
            throw ex;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 저장 실패의 원인을 임시 파일 정리 실패로 바꾸지 않는다.
                }
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

    static void atomicMove(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void warnUnsupportedSecureDirectoryOnce() {
        if (UNSUPPORTED_SECURE_DIRECTORY_WARNING_LOGGED.compareAndSet(false, true)) {
            log.warn("SecureDirectoryStream is unavailable; using checked atomic artifact replacement");
        }
    }
}
