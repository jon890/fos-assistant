package com.bifos.assistant.browser.infra;

import com.bifos.assistant.browser.domain.BrowserProfileStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 프로필 루트 아래에 프로필 키 이름의 디렉터리를 둔다.
 *
 * <p>디렉터리 이름은 키 하나뿐이라 루트 밖을 가리킬 수 없다. 지울 때는 링크를 따라가지 않는다. 디렉터리 안에 루트 밖을 가리키는 링크가
 * 있어도 링크만 지우고 가리키는 곳은 그대로 둔다.
 */
@Component
@RequiredArgsConstructor
public class FileBrowserProfileStore implements BrowserProfileStore {

    private static final Pattern KEY = Pattern.compile("^[0-9a-f]{64}$");

    private final BrowserProperties properties;

    @Override
    public void ensure(String profileKey) {
        Path dir = directory(profileKey);
        if (Files.isSymbolicLink(dir)) {
            throw new IllegalStateException("browser profile directory is a link");
        }
        if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.createDirectories(dir.getParent());
            Files.createDirectory(dir);
            if (Files.getFileStore(dir).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot create browser profile directory", ex);
        }
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
        String root = properties.profileRoot();
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("assistant.browser.profile-root is not configured");
        }
        return Path.of(root).toAbsolutePath().normalize().resolve(profileKey);
    }
}
