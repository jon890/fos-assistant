package com.bifos.assistant.skill.infra;

import static com.bifos.assistant.skill.infra.SkillFilePaths.SKILL_MD;
import static com.bifos.assistant.skill.infra.SkillFilePaths.isScript;
import static com.bifos.assistant.skill.infra.SkillFilePaths.isValidFilePath;

import com.bifos.assistant.hermes.HermesProfileName;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.domain.SkillBundle;
import com.bifos.assistant.skill.domain.SkillFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 스킬 저장소의 디렉터리와 파일을 링크를 따라가지 않고 쓰고 읽고 지우는 공용 도우미다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@Slf4j
final class SkillFiles {

    static final String TEMP_PREFIX = ".tmp-";

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwxr-xr-x");
    static final Set<PosixFilePermission> FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-r--r--");
    private static final Set<PosixFilePermission> SCRIPT_PERMISSIONS = PosixFilePermissions.fromString("rwxr-xr-x");

    private static final String VERSION_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int VERSION_RANDOM_CHARS = 4;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 스킬 디렉터리 전체를 링크를 따라가지 않고 걸어 읽는다.
     *
     * <p>이름이 점으로 시작하는 파일과 디렉터리는 건너뛰고 그 아래로 내려가지 않는다. 표식과 남긴 시각 파일이
     * 그렇다. 나머지에서 링크나 일반 파일·디렉터리가 아닌 항목을 만나면 경로와 관계없이 {@link IOException} 이다.
     * plugin 도 버전 디렉터리 안의 모든 링크를 거절한다. {@code SKILL.md} 를 뺀 일반 파일 가운데 경로 규칙에 맞는
     * 것만 읽고 나머지는 건너뛴다. 결과는 경로 차례다.
     */
    static SkillBundle readBundle(Path skillDir, String name) throws IOException {
        Path skillMd = skillDir.resolve(SKILL_MD);
        requireRegularFile(skillMd);
        List<SkillFile> files = new ArrayList<>();
        Files.walkFileTree(skillDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                if (!dir.equals(skillDir) && isDotName(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (isDotName(file)) {
                    return FileVisitResult.CONTINUE;
                }
                if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                    throw new IOException("skill path is not a plain file or directory: " + skillDir.relativize(file));
                }
                String path = relativePath(skillDir, file);
                if (!path.equals(SKILL_MD) && isValidFilePath(path)) {
                    files.add(new SkillFile(path, Files.readString(file, StandardCharsets.UTF_8)));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException ex) throws IOException {
                throw ex;
            }
        });
        files.sort(Comparator.comparing(SkillFile::path));
        return new SkillBundle(name, Files.readString(skillMd, StandardCharsets.UTF_8), files);
    }

    private static boolean isDotName(Path path) {
        return path.getFileName().toString().startsWith(".");
    }

    private static String relativePath(Path base, Path file) {
        Path relative = base.relativize(file);
        List<String> segments = new ArrayList<>();
        relative.forEach(segment -> segments.add(segment.toString()));
        return String.join("/", segments);
    }

    /**
     * 스킬 하나를 그 디렉터리에 쓴다. 파일의 부모 디렉터리는 스킬 디렉터리부터 한 단계씩 만들어 중간 디렉터리도
     * umask 를 따르지 않고 755 가 되게 한다. {@code scripts/} 아래 파일만 755 로 쓴다.
     */
    static void writeSkill(Path root, Path skillDir, SkillBundle bundle) throws IOException {
        createDirectory(skillDir);
        writeFile(skillDir.resolve(SKILL_MD), bundle.skillMd(), FILE_PERMISSIONS);
        for (SkillFile file : bundle.files()) {
            Path target = resolveInside(root, skillDir, file.path());
            Path dir = skillDir;
            for (Path segment : skillDir.relativize(target.getParent())) {
                if (segment.toString().isEmpty()) {
                    continue;
                }
                dir = dir.resolve(segment.toString());
                createDirectory(dir);
            }
            writeFile(target, file.content(), isScript(file.path()) ? SCRIPT_PERMISSIONS : FILE_PERMISSIONS);
        }
    }

    static String randomSuffix() {
        StringBuilder suffix = new StringBuilder(VERSION_RANDOM_CHARS);
        for (int i = 0; i < VERSION_RANDOM_CHARS; i++) {
            suffix.append(VERSION_ALPHABET.charAt(RANDOM.nextInt(VERSION_ALPHABET.length())));
        }
        return suffix.toString();
    }

    /** 정규화한 결과가 {@code base} 아래가 아니면 거절한다. 이름 검사를 지나온 값도 한 번 더 본다. */
    static Path resolveInside(Path root, Path base, String relative) {
        Path resolved = base.resolve(relative).normalize();
        if (!resolved.startsWith(base) || resolved.equals(base) || !resolved.startsWith(root)) {
            throw validation("skill path escapes the skill root");
        }
        return resolved;
    }

    static void requireProfile(String profile) {
        if (!HermesProfileName.isValid(profile)) {
            throw validation("profile name is not a valid Hermes profile");
        }
    }

    static void requireDirectory(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("skill path is not a plain directory: " + path.getFileName());
        }
    }

    static void requireRegularFile(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("skill path is not a plain file: " + path.getFileName());
        }
    }

    static void createDirectory(Path dir) throws IOException {
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            requireDirectory(dir);
            return;
        }
        Files.createDirectories(dir);
        setPermissions(dir, DIRECTORY_PERMISSIONS);
    }

    static void writeFile(Path file, String content) throws IOException {
        writeFile(file, content, FILE_PERMISSIONS);
    }

    static void writeFile(Path file, String content, Set<PosixFilePermission> permissions) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
        setPermissions(file, permissions);
    }

    /** POSIX 권한을 주지 못하는 파일 시스템에서는 그대로 둔다. */
    static void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // POSIX 가 아닌 파일 시스템이다. 운영은 Linux 라 여기 오지 않는다.
        }
    }

    static void deleteQuietly(Path dir) {
        try {
            deleteRecursively(dir);
        } catch (RuntimeException ex) {
            log.warn("쓰다 실패한 스킬 임시 디렉터리를 지우지 못했다 path={}", dir.getFileName(), ex);
        }
    }

    /** 심볼릭 링크는 따라가지 않고 링크 자체만 지운다. 없으면 그냥 지나간다. */
    static void deleteRecursively(Path dir) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException ex) throws IOException {
                    if (ex != null) {
                        throw ex;
                    }
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            throw storeFailure(ex);
        }
    }

    static ApiException validation(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }

    static ApiException storeFailure(IOException cause) {
        return new ApiException(ErrorCode.INTERNAL_ERROR, "could not access the skill store", cause);
    }
}
