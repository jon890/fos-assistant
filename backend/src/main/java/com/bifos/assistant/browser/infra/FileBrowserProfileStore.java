package com.bifos.assistant.browser.infra;

import com.bifos.assistant.browser.domain.BrowserProfileStore;
import com.bifos.assistant.shared.config.LiveProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 프로필 루트 아래에 프로필 키 이름의 디렉터리를 둔다.
 *
 * <p>디렉터리 이름은 키 하나뿐이라 루트 밖을 가리킬 수 없다. 지울 때는 링크를 따라가지 않는다. 디렉터리 안에 루트 밖을 가리키는 링크가
 * 있어도 링크만 지우고 가리키는 곳은 그대로 둔다.
 *
 * <p>새 프로필에는 Chrome 이 이전 세션을 이어서 열도록 {@code Default/Preferences} 를 써 둔다. 정상 종료한 뒤 다음 기동에서 세션
 * 쿠키가 돌아온다. 이미 있는 설정 파일은 Chrome 이 고쳐 쓰는 것이라 건드리지 않는다.
 */
@Component
@RequiredArgsConstructor
public class FileBrowserProfileStore implements BrowserProfileStore {

    private static final Pattern KEY = Pattern.compile("^[0-9a-f]{64}$");
    /** Chrome 의 「이전 세션 이어서 열기」 다. */
    private static final byte[] SESSION_RESTORE =
            "{\"session\":{\"restore_on_startup\":1}}".getBytes(StandardCharsets.UTF_8);

    private final LiveProperties<BrowserProperties> properties;

    @Override
    public void ensure(String profileKey) {
        Path dir = directory(profileKey);
        if (Files.isSymbolicLink(dir)) {
            throw new IllegalStateException("browser profile directory is a link");
        }
        try {
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(dir.getParent());
                createOwnerOnlyDirectory(dir);
            }
            writeSessionRestore(dir);
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot create browser profile directory", ex);
        }
    }

    /** {@code Default/Preferences} 가 없을 때만 세션 이어가기 설정을 쓴다. 링크를 따라가지 않는다. */
    private static void writeSessionRestore(Path dir) throws IOException {
        Path defaults = dir.resolve("Default");
        if (Files.isSymbolicLink(defaults)) {
            throw new IllegalStateException("browser profile Default directory is a link");
        }
        if (!Files.isDirectory(defaults, LinkOption.NOFOLLOW_LINKS)) {
            createOwnerOnlyDirectory(defaults);
        }
        Path preferences = defaults.resolve("Preferences");
        if (Files.exists(preferences, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        // CREATE_NEW 는 그 자리에 링크가 생겨 있어도 따라가지 않고 실패한다. 권한은 만들 때 함께 준다
        Set<OpenOption> options =
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = posix(defaults)
                ? Files.newByteChannel(
                        preferences,
                        options,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.newByteChannel(preferences, options)) {
            ByteBuffer content = ByteBuffer.wrap(SESSION_RESTORE);
            while (content.hasRemaining()) {
                channel.write(content);
            }
        } catch (FileAlreadyExistsException ex) {
            // 같은 프로필을 동시에 만든 다른 요청이 먼저 썼다
        }
    }

    /** 권한을 만들 때 함께 준다. 같은 자리를 동시에 만든 다른 요청이 먼저 만들었으면 그 디렉터리를 그대로 쓴다. */
    private static void createOwnerOnlyDirectory(Path dir) throws IOException {
        try {
            if (posix(dir.getParent())) {
                Files.createDirectory(
                        dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } else {
                Files.createDirectory(dir);
            }
        } catch (FileAlreadyExistsException ex) {
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("browser profile directory is not a directory", ex);
            }
        }
    }

    private static boolean posix(Path dir) throws IOException {
        return Files.getFileStore(dir).supportsFileAttributeView("posix");
    }

    @Override
    public void delete(String profileKey) {
        Path dir = directory(profileKey);
        try {
            if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                // 링크나 파일이면 그것만 지운다. 가리키는 곳을 따라가지 않는다
                Files.delete(dir);
                return;
            }
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path visited, IOException failure) throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    Files.delete(visited);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot delete browser profile directory", ex);
        }
    }

    private Path directory(String profileKey) {
        if (profileKey == null || !KEY.matcher(profileKey).matches()) {
            throw new IllegalArgumentException("browser profile key is not valid");
        }
        String root = properties.current().profileRoot();
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("assistant.browser.profile-root is not configured");
        }
        return Path.of(root).toAbsolutePath().normalize().resolve(profileKey);
    }
}
