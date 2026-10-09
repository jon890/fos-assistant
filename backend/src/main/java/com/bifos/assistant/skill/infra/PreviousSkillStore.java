package com.bifos.assistant.skill.infra;

import static com.bifos.assistant.skill.infra.SkillFilePaths.requireBundle;
import static com.bifos.assistant.skill.infra.SkillFilePaths.requireSkillName;
import static com.bifos.assistant.skill.infra.SkillFiles.FILE_PERMISSIONS;
import static com.bifos.assistant.skill.infra.SkillFiles.TEMP_PREFIX;
import static com.bifos.assistant.skill.infra.SkillFiles.createDirectory;
import static com.bifos.assistant.skill.infra.SkillFiles.deleteQuietly;
import static com.bifos.assistant.skill.infra.SkillFiles.deleteRecursively;
import static com.bifos.assistant.skill.infra.SkillFiles.randomSuffix;
import static com.bifos.assistant.skill.infra.SkillFiles.readBundle;
import static com.bifos.assistant.skill.infra.SkillFiles.requireDirectory;
import static com.bifos.assistant.skill.infra.SkillFiles.requireProfile;
import static com.bifos.assistant.skill.infra.SkillFiles.requireRegularFile;
import static com.bifos.assistant.skill.infra.SkillFiles.storeFailure;
import static com.bifos.assistant.skill.infra.SkillFiles.validation;
import static com.bifos.assistant.skill.infra.SkillFiles.writeFile;

import com.bifos.assistant.skill.domain.SkillBundle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 스킬마다 바뀌기 전 것 하나를 {@code {root}/{profile}/.previous/{스킬}} 에 쓰고 읽고 지운다. Hermes 는 읽지 않는다.
 * 근거는 ADR-20261009-skill-package 에 있다.
 */
@Component
@Slf4j
public class PreviousSkillStore {

    /** profile 디렉터리 안에 스킬마다 이전 버전 하나를 두는 디렉터리다. Hermes 는 읽지 않는다. */
    public static final String PREVIOUS_DIR = ".previous";

    /** 이전 버전 디렉터리 안에 그 버전을 남긴 시각(UTC 밀리초)을 적는 파일이다. */
    public static final String SAVED_AT = ".saved-at";

    /** 이전 버전을 바꿀 때 옛것을 잠시 옮겨 두는 이름의 앞부분이다. */
    private static final String OLD_PREFIX = ".old-";

    private final Path root;
    private final Clock clock;

    public PreviousSkillStore(SkillProperties properties, Clock clock) {
        this.clock = clock;
        this.root = Path.of(properties.root()).toAbsolutePath().normalize();
    }

    /**
     * 바뀌기 전 스킬을 그 스킬의 이전 버전으로 쓴다. 있던 이전 버전은 바뀐다.
     *
     * <p>임시 디렉터리에 다 쓴 뒤 옮긴다. 있던 이전 버전은 먼저 옆 이름으로 옮겨 두고, 새 디렉터리를 제자리로 옮긴 뒤에
     * 지운다. 새것을 옮기지 못하면 옛것을 되돌린다. 두 이동 사이에 프로세스가 멈추거나 되돌리기도 실패하면 옛것은
     * {@code .old-<이름>-*} 로 남는다. 두 이동 사이에는 이전 버전이 잠깐 없다. 남긴 시각을 {@link #SAVED_AT} 에 UTC 밀리초로
     * 쓴다. 파일 권한은 {@link SkillStore#writeVersion} 과 같다.
     *
     * <p>시작할 때 {@code .previous} 아래에 남은 {@code .old-} 와 {@code .tmp-} 항목을 지운다. 에이전트 행 잠금 안에서만
     * 불리므로 도는 쓰기와 겹치지 않는다. 지우기가 실패하면 경고 로그만 남긴다.
     */
    public void writePrevious(String profile, SkillBundle bundle) {
        if (bundle == null) {
            throw validation("skill bundle is missing");
        }
        requireBundle(bundle);
        Path previousRoot = resolveInside(profileDir(profile), PREVIOUS_DIR);
        Path target = resolveInside(previousRoot, bundle.name());
        Path temporary = null;
        try {
            createDirectory(previousRoot.getParent());
            createDirectory(previousRoot);
            removeLeftovers(previousRoot);
            temporary = resolveInside(previousRoot, TEMP_PREFIX + bundle.name() + "-" + randomSuffix());
            SkillFiles.writeSkill(root, temporary, bundle);
            writeFile(temporary.resolve(SAVED_AT), Long.toString(clock.millis()), FILE_PERMISSIONS);
            Path old = null;
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                old = resolveInside(previousRoot, OLD_PREFIX + bundle.name() + "-" + randomSuffix());
                Files.move(target, old, StandardCopyOption.ATOMIC_MOVE);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                temporary = null;
            } catch (IOException ex) {
                if (old != null) {
                    restoreOld(old, target, ex);
                }
                throw ex;
            }
            if (old != null) {
                deleteQuietly(old);
            }
        } catch (IOException ex) {
            throw storeFailure(ex);
        } finally {
            if (temporary != null) {
                deleteQuietly(temporary);
            }
        }
    }

    /** 그 스킬의 이전 버전이다. 없으면 빈 값이다. */
    public Optional<PreviousSkill> readPrevious(String profile, String name) {
        Path skillDir = previousSkillDir(profile, name);
        if (!Files.exists(skillDir, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            requirePreviousSkillDir(skillDir);
            SkillBundle bundle = readBundle(skillDir, name);
            return Optional.of(new PreviousSkill(bundle, readSavedAt(skillDir)));
        } catch (IOException ex) {
            throw storeFailure(ex);
        }
    }

    /** 그 스킬의 이전 버전을 남긴 시각이다. {@link #SAVED_AT} 만 읽고 본문은 읽지 않는다. 없으면 빈 값이다. */
    public Optional<Instant> previousSavedAt(String profile, String name) {
        Path skillDir = previousSkillDir(profile, name);
        if (!Files.exists(skillDir, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            requirePreviousSkillDir(skillDir);
            return Optional.of(readSavedAt(skillDir));
        } catch (IOException ex) {
            throw storeFailure(ex);
        }
    }

    /** 그 스킬의 이전 버전을 지운다. 없으면 그냥 지나간다. {@code .previous} 가 링크면 지우지 않고 거절한다. */
    public void deletePrevious(String profile, String name) {
        Path skillDir = previousSkillDir(profile, name);
        try {
            requirePreviousRootIfPresent(profileDir(profile));
        } catch (IOException ex) {
            throw storeFailure(ex);
        }
        deleteRecursively(skillDir);
    }

    private Path previousSkillDir(String profile, String name) {
        requireSkillName(name);
        return resolveInside(resolveInside(profileDir(profile), PREVIOUS_DIR), name);
    }

    /** 이전 버전 디렉터리와 그 위 {@code .previous} 가 링크가 아닌 디렉터리인지 본다. */
    private static void requirePreviousSkillDir(Path skillDir) throws IOException {
        requireDirectory(skillDir.getParent());
        requireDirectory(skillDir);
    }

    /** {@code .previous} 가 있으면 링크가 아닌 디렉터리인지 본다. */
    static void requirePreviousRootIfPresent(Path profileDir) throws IOException {
        Path previousRoot = profileDir.resolve(PREVIOUS_DIR);
        if (Files.exists(previousRoot, LinkOption.NOFOLLOW_LINKS)) {
            requireDirectory(previousRoot);
        }
    }

    private static Instant readSavedAt(Path skillDir) throws IOException {
        Path savedAt = skillDir.resolve(SAVED_AT);
        requireRegularFile(savedAt);
        String millis = Files.readString(savedAt, StandardCharsets.UTF_8).strip();
        try {
            return Instant.ofEpochMilli(Long.parseLong(millis));
        } catch (NumberFormatException ex) {
            throw new IOException("skill previous version has an invalid saved time", ex);
        }
    }

    /** 앞선 쓰기가 남긴 {@code .old-} 와 {@code .tmp-} 항목을 지운다. 실패해도 쓰기를 막지 않고 경고 로그만 남긴다. */
    private static void removeLeftovers(Path previousRoot) {
        List<Path> leftovers = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(previousRoot)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.startsWith(OLD_PREFIX) || name.startsWith(TEMP_PREFIX)) {
                    leftovers.add(entry);
                }
            }
        } catch (IOException ex) {
            log.warn("남은 이전 버전 임시 항목을 훑지 못했다", ex);
            return;
        }
        for (Path leftover : leftovers) {
            try {
                deleteRecursively(leftover);
            } catch (RuntimeException ex) {
                log.warn("남은 이전 버전 임시 항목을 지우지 못했다 path={}", leftover.getFileName(), ex);
            }
        }
    }

    /** 새 이전 버전을 옮기지 못했을 때 옮겨 둔 옛것을 제자리로 되돌린다. 되돌리기도 실패하면 원래 오류에 붙인다. */
    private static void restoreOld(Path old, Path target, IOException cause) {
        try {
            Files.move(old, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException restoreFailure) {
            cause.addSuppressed(restoreFailure);
            log.warn("옮겨 둔 이전 버전을 되돌리지 못했다 path={}", old.getFileName(), restoreFailure);
        }
    }

    private Path profileDir(String profile) {
        requireProfile(profile);
        return resolveInside(root, profile);
    }

    private Path resolveInside(Path base, String relative) {
        return SkillFiles.resolveInside(root, base, relative);
    }
}
