package com.bifos.assistant.skill.infra;

import com.bifos.assistant.hermes.HermesProfileName;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.domain.SkillBundle;
import com.bifos.assistant.skill.domain.SkillFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * profile 마다 둔 스킬 버전 디렉터리를 쓰고 읽고 지운다.
 *
 * <p>경로를 만드는 규칙이 이 클래스 하나에만 있다. 디렉터리는 {@code {root}/{profile}/{버전}/{스킬}}
 * 이고 스킬 안은 {@code SKILL.md} 와 경로 규칙({@link #requireFilePath})에 맞는 파일이다. 게시에 성공한 버전에는
 * 표식 파일 {@code .published} 가 있고, 지금 버전은 표식이 있는 가장 새 디렉터리다. 스킬마다 바뀌기 전 것 하나를
 * {@code {root}/{profile}/.previous/{스킬}} 에 둔다. Hermes 설정을 읽지 않는다. 근거는 ADR-034 와
 * ADR-20261009-skill-package 에 있다.
 *
 * <p>새 버전은 임시 디렉터리에 다 쓴 뒤 원자 이동한다. 쓰는 도중의 반쯤 바뀐 스킬을 실행이 읽지 않게
 * 하기 위해서다. Hermes 가 읽을 수 있게 파일은 644, 디렉터리는 755 로 쓴다. 실행 공간이 돌릴 수 있게
 * {@code scripts/} 아래 파일만 755 로 쓴다.
 */
@Component
@Slf4j
public class SkillStore {

    /** 게시에 성공한 버전 디렉터리 안에 두는 표식 파일이다. */
    public static final String PUBLISHED_MARKER = ".published";

    public static final String SKILL_MD = "SKILL.md";

    /** 새 스킬 화면의 경로라 스킬 이름으로 쓸 수 없다. */
    public static final String RESERVED_SKILL_NAME = "new";

    /** profile 디렉터리 안에 스킬마다 이전 버전 하나를 두는 디렉터리다. Hermes 는 읽지 않는다. */
    public static final String PREVIOUS_DIR = ".previous";

    /** 이전 버전 디렉터리 안에 그 버전을 남긴 시각(UTC 밀리초)을 적는 파일이다. */
    public static final String SAVED_AT = ".saved-at";

    private static final String TEMP_PREFIX = ".tmp-";

    /** 이전 버전을 바꿀 때 옛것을 잠시 옮겨 두는 이름의 앞부분이다. */
    private static final String OLD_PREFIX = ".old-";

    /** 버전 이름. plugin 이 이 형식만 받는다. 앞 13자리는 UTC 밀리초라 이름 차례가 곧 시간 차례다. */
    private static final Pattern VERSION = Pattern.compile("v([0-9]{13})-[a-z0-9]{4}");

    private static final Pattern SKILL_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    /** 파일 경로의 한 조각. 점으로 시작하지 못하므로 {@code ..} 과 숨은 파일이 들어오지 못한다. */
    private static final Pattern PATH_SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    private static final int MAX_PATH_SEGMENTS = 4;
    private static final int MAX_PATH_LENGTH = 200;

    /** 두 조각 이상인 경로의 첫 조각이 될 수 있는 디렉터리다. */
    private static final Set<String> FILE_DIRECTORIES = Set.of("references", "templates", "scripts", "assets");

    private static final String SCRIPTS_PREFIX = "scripts/";

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwxr-xr-x");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-r--r--");
    private static final Set<PosixFilePermission> SCRIPT_PERMISSIONS = PosixFilePermissions.fromString("rwxr-xr-x");

    private static final String VERSION_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int VERSION_RANDOM_CHARS = 4;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path root;
    private final String agentRoot;
    private final int keepVersions;
    private final Clock clock;

    public SkillStore(SkillProperties properties, Clock clock) {
        this.clock = clock;
        this.root = Path.of(properties.root()).toAbsolutePath().normalize();
        this.agentRoot = stripTrailingSlash(properties.agentRoot());
        this.keepVersions = properties.keepVersions();
    }

    /** 스킬 이름이 규칙에 맞는지 본다. 아니면 {@link ErrorCode#VALIDATION_FAILED} 다. */
    public static void requireSkillName(String name) {
        if (name == null || !SKILL_NAME.matcher(name).matches() || RESERVED_SKILL_NAME.equals(name)) {
            throw validation("skill name must be lowercase letters, digits and dashes and not 'new'");
        }
    }

    /**
     * {@code SKILL.md} 를 뺀 스킬 파일의 경로가 규칙에 맞는지 본다. 아니면 {@link ErrorCode#VALIDATION_FAILED} 다.
     *
     * <p>{@code /} 로 이은 1~4 조각, 200자까지다. 한 조각이면 {@code .md} 나 {@code .txt} 로 끝나는 맨 위 파일이고, 둘
     * 이상이면 첫 조각이 {@code references}, {@code templates}, {@code scripts}, {@code assets} 가운데 하나다. 마지막
     * 조각이 대소문자 무시로 {@code SKILL.md} 이면 받지 않는다. Hermes 는 외부 디렉터리 아래의 모든 {@code SKILL.md} 를
     * 스킬로 읽는다.
     */
    public static void requireFilePath(String path) {
        if (!isValidFilePath(path)) {
            throw validation("skill file paths must follow the skill file path rule");
        }
    }

    /**
     * 한 스킬의 파일 경로들이 함께 놓일 수 있는지 본다. 대소문자만 다른 두 경로와, 한 경로가 다른 경로의 디렉터리
     * 앞부분인 경우({@code a/b} 와 {@code a/b/c.md})를 {@link ErrorCode#VALIDATION_FAILED} 로 거절한다.
     */
    public static void requireFileSet(Collection<String> paths) {
        Set<String> folded = new HashSet<>();
        for (String path : paths) {
            if (!folded.add(path.toLowerCase(Locale.ROOT))) {
                throw validation("a skill file path was sent more than once, ignoring case: " + path);
            }
        }
        for (String path : paths) {
            String lower = path.toLowerCase(Locale.ROOT);
            for (int slash = lower.indexOf('/'); slash >= 0; slash = lower.indexOf('/', slash + 1)) {
                if (folded.contains(lower.substring(0, slash))) {
                    throw validation("a skill file path is also a directory of another file: " + path);
                }
            }
        }
    }

    /** 실행 공간에서 돌리는 스크립트 경로인가. {@code scripts/} 로 시작하면 참이다. */
    public static boolean isScript(String path) {
        return path != null && path.startsWith(SCRIPTS_PREFIX);
    }

    private static boolean isValidFilePath(String path) {
        if (path == null || path.isEmpty() || path.length() > MAX_PATH_LENGTH) {
            return false;
        }
        String[] segments = path.split("/", -1);
        if (segments.length > MAX_PATH_SEGMENTS) {
            return false;
        }
        for (String segment : segments) {
            if (!PATH_SEGMENT.matcher(segment).matches()) {
                return false;
            }
        }
        String last = segments[segments.length - 1];
        if (last.equalsIgnoreCase(SKILL_MD)) {
            return false;
        }
        if (segments.length == 1) {
            String lower = last.toLowerCase(Locale.ROOT);
            return lower.endsWith(".md") || lower.endsWith(".txt");
        }
        return FILE_DIRECTORIES.contains(segments[0]);
    }

    /** 표식이 있는 가장 새 버전이다. 없으면 빈 값이다. */
    public Optional<String> currentVersion(String profile) {
        Path profileDir = profileDir(profile);
        if (!Files.isDirectory(profileDir, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        return versionNames(profileDir).stream()
                .filter(version -> isPublished(profileDir.resolve(version)))
                .max(Comparator.naturalOrder());
    }

    /** 지금 버전의 스킬들이다. 이름 차례이고, 지금 버전이 없으면 비어 있다. */
    public Map<String, SkillBundle> readCurrent(String profile) {
        Optional<String> version = currentVersion(profile);
        if (version.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(readVersion(profile, version.get()));
    }

    /**
     * 지금 버전보다 새로 쓰였지만 표식이 없는 버전들의 스킬이다. 같은 이름이면 더 새 버전의 것을 쓴다.
     *
     * <p>게시가 timeout 이나 5xx 로 끝난 버전이다. Hermes 가 이미 반영해 그 버전을 가리키고 있을 수 있어서,
     * 거기 든 이름은 우리가 올린 스킬로 다뤄야 한다. 그러지 않으면 Hermes 목록에 뜬 그 이름을 Hermes 자체
     * 스킬로 읽어 다시 저장하지도 지우지도 못한다. 지금 버전보다 오래된 표식 없는 버전은 Hermes 가 이미 새
     * 게시로 넘어간 것이라 보지 않는다.
     */
    public Map<String, SkillBundle> readPending(String profile) {
        Map<String, SkillBundle> bundles = new TreeMap<>();
        for (SkillBundle bundle : readAllPending(profile)) {
            bundles.put(bundle.name(), bundle);
        }
        return new LinkedHashMap<>(bundles);
    }

    /** 표식 없는 더 새 버전의 스킬을 모두 읽는다. 비밀 요청 검사가 같은 이름의 어느 버전도 빠뜨리지 않게 한다. */
    public List<SkillBundle> readAllPending(String profile) {
        Path profileDir = profileDir(profile);
        if (!Files.isDirectory(profileDir, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        String current = currentVersion(profile).orElse("");
        List<String> pending = versionNames(profileDir).stream()
                .filter(version -> version.compareTo(current) > 0)
                .filter(version -> !isPublished(profileDir.resolve(version)))
                .sorted()
                .toList();
        List<SkillBundle> bundles = new ArrayList<>();
        for (String version : pending) {
            bundles.addAll(readVersion(profile, version).values());
        }
        return List.copyOf(bundles);
    }

    /**
     * Hermes 가 가리킬 수 있는 버전에 스킬이 하나라도 있는가. 지금 버전과 표식 없는 더 새 버전을 본다.
     * 도구 저장이 {@code skills} 를 끄지 못하게 할 때 본다.
     */
    public boolean hasUploadedSkills(String profile) {
        return !readCurrent(profile).isEmpty() || !readPending(profile).isEmpty();
    }

    private Map<String, SkillBundle> readVersion(String profile, String version) {
        Path versionDir = versionDir(profile, version);
        Map<String, SkillBundle> bundles = new TreeMap<>();
        try {
            for (String name : skillNames(versionDir)) {
                bundles.put(name, readBundle(versionDir.resolve(name), name));
            }
        } catch (IOException ex) {
            throw storeFailure(ex);
        }
        return bundles;
    }

    /**
     * 스킬 전체를 새 버전 디렉터리에 쓰고 그 버전 이름을 돌려준다.
     *
     * <p>임시 디렉터리에 다 쓴 뒤 원자 이동한다. 쓰다 실패하면 임시 디렉터리를 지우고 오류를 올린다. 새
     * 버전 이름은 이미 있는 어느 버전보다 뒤에 오게 만든다. 이름 차례로 지금 버전을 고르기 때문이다.
     */
    public String writeVersion(String profile, Map<String, SkillBundle> skills) {
        Path profileDir = profileDir(profile);
        for (Map.Entry<String, SkillBundle> entry : skills.entrySet()) {
            SkillBundle bundle = entry.getValue();
            if (bundle == null || !entry.getKey().equals(bundle.name())) {
                throw validation("skill bundle does not match its name");
            }
            requireBundle(bundle);
        }
        Path temporary = null;
        try {
            createDirectory(profileDir);
            String version = newVersionName(profileDir);
            temporary = profileDir.resolve(TEMP_PREFIX + version);
            createDirectory(temporary);
            for (SkillBundle bundle : skills.values()) {
                writeSkill(resolveInside(temporary, bundle.name()), bundle);
            }
            Files.move(temporary, profileDir.resolve(version), StandardCopyOption.ATOMIC_MOVE);
            temporary = null;
            return version;
        } catch (IOException ex) {
            throw storeFailure(ex);
        } finally {
            if (temporary != null) {
                deleteQuietly(temporary);
            }
        }
    }

    /** 게시에 성공한 버전에 표식을 쓴다. 이때부터 그 버전이 지금 버전이다. */
    public void markPublished(String profile, String version) {
        Path versionDir = versionDir(profile, version);
        if (!Files.isDirectory(versionDir, LinkOption.NOFOLLOW_LINKS)) {
            throw storeFailure(new IOException("skill version directory is missing"));
        }
        try {
            writeFile(versionDir.resolve(PUBLISHED_MARKER), "");
        } catch (IOException ex) {
            throw storeFailure(ex);
        }
    }

    /** 게시가 분명히 거절된 버전을 지운다. 없으면 그냥 지나간다. */
    public void discard(String profile, String version) {
        deleteRecursively(versionDir(profile, version));
    }

    /** 그 profile 디렉터리를 통째로 지운다. 에이전트를 지울 때 부른다. */
    public void deleteAll(String profile) {
        deleteRecursively(profileDir(profile));
    }

    /**
     * 그 profile 의 버전 디렉터리와 이전 버전, 남은 임시 디렉터리를 지우고 profile 디렉터리는 남긴다. 마지막 스킬을
     * 지울 때 부른다. 실행 공간이 profile 디렉터리를 붙이고 있어서 지우면 다음 컨테이너 생성이 없는 원본을 만난다.
     */
    public void clearVersions(String profile) {
        Path profileDir = profileDir(profile);
        if (!Files.isDirectory(profileDir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> targets = new ArrayList<>();
        try {
            // 링크인 .previous 를 지우는 대신 거절한다. 무엇이 그 자리에 링크를 두었는지 알아야 해서다.
            requirePreviousRootIfPresent(profileDir);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(profileDir)) {
                for (Path entry : stream) {
                    String name = entry.getFileName().toString();
                    if (VERSION.matcher(name).matches() || PREVIOUS_DIR.equals(name) || name.startsWith(TEMP_PREFIX)) {
                        targets.add(entry);
                    }
                }
            }
        } catch (IOException ex) {
            throw storeFailure(ex);
        }
        targets.forEach(SkillStore::deleteRecursively);
    }

    /**
     * 바뀌기 전 스킬을 그 스킬의 이전 버전으로 쓴다. 있던 이전 버전은 바뀐다.
     *
     * <p>임시 디렉터리에 다 쓴 뒤 옮긴다. 있던 이전 버전은 먼저 옆 이름으로 옮겨 두고, 새 디렉터리를 제자리로 옮긴 뒤에
     * 지운다. 새것을 옮기지 못하면 옛것을 되돌린다. 두 이동 사이에 프로세스가 멈추거나 되돌리기도 실패하면 옛것은
     * {@code .old-<이름>-*} 로 남는다. 두 이동 사이에는 이전 버전이 잠깐 없다. 남긴 시각을 {@link #SAVED_AT} 에 UTC 밀리초로
     * 쓴다. 파일 권한은 {@link #writeVersion} 과 같다.
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
            writeSkill(temporary, bundle);
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
    private static void requirePreviousRootIfPresent(Path profileDir) throws IOException {
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

    /**
     * 옛 버전을 지운다.
     *
     * <p>표식 있는 버전은 최근 {@code keepVersions} 개를 남긴다. 표식 없는 디렉터리는 {@code
     * publishedVersion} 보다 오래된 것만 지운다. 게시가 timeout 으로 끝나 표식이 없어도 Hermes 가
     * 반영했을 수 있으므로, 그보다 새 게시가 성공한 뒤에만 지운다. 남은 임시 디렉터리도 함께 지운다.
     * 하나가 실패해도 나머지를 계속하고 게시를 실패로 바꾸지 않는다.
     */
    public void prune(String profile, String publishedVersion) {
        Path profileDir = profileDir(profile);
        requireVersion(publishedVersion);
        if (!Files.isDirectory(profileDir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<String> versions = versionNames(profileDir);
        versions.sort(Comparator.reverseOrder());
        int kept = 0;
        for (String version : versions) {
            Path dir = profileDir.resolve(version);
            if (isPublished(dir)) {
                kept++;
                if (kept > keepVersions) {
                    deleteForPrune(dir);
                }
            } else if (version.compareTo(publishedVersion) < 0) {
                deleteForPrune(dir);
            }
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(profileDir)) {
            for (Path entry : stream) {
                if (entry.getFileName().toString().startsWith(TEMP_PREFIX)) {
                    deleteForPrune(entry);
                }
            }
        } catch (IOException ex) {
            log.warn("스킬 임시 디렉터리를 훑지 못했다 profile={}", profile, ex);
        }
    }

    /** 그 버전 디렉터리를 Hermes 컨테이너에서 보는 경로다. {@code external_dirs} 에 이것을 적는다. */
    public String agentPath(String profile, String version) {
        requireProfile(profile);
        requireVersion(version);
        return agentRoot + "/" + profile + "/" + version;
    }

    /**
     * 스킬 디렉터리 전체를 링크를 따라가지 않고 걸어 읽는다.
     *
     * <p>이름이 점으로 시작하는 파일과 디렉터리는 건너뛰고 그 아래로 내려가지 않는다. 표식과 남긴 시각 파일이
     * 그렇다. 나머지에서 링크나 일반 파일·디렉터리가 아닌 항목을 만나면 경로와 관계없이 {@link IOException} 이다.
     * plugin 도 버전 디렉터리 안의 모든 링크를 거절한다. {@code SKILL.md} 를 뺀 일반 파일 가운데 경로 규칙에 맞는
     * 것만 읽고 나머지는 건너뛴다. 결과는 경로 차례다.
     */
    private static SkillBundle readBundle(Path skillDir, String name) throws IOException {
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

    /** 이름, {@code SKILL.md}, 파일 경로와 그 묶음을 본다. 아니면 {@link ErrorCode#VALIDATION_FAILED} 다. */
    private static void requireBundle(SkillBundle bundle) {
        requireSkillName(bundle.name());
        if (bundle.skillMd() == null) {
            throw validation("skill bundle does not match its name");
        }
        bundle.files().forEach(file -> requireFilePath(file.path()));
        requireFileSet(bundle.files().stream().map(SkillFile::path).toList());
    }

    /**
     * 스킬 하나를 그 디렉터리에 쓴다. 파일의 부모 디렉터리는 스킬 디렉터리부터 한 단계씩 만들어 중간 디렉터리도
     * umask 를 따르지 않고 755 가 되게 한다. {@code scripts/} 아래 파일만 755 로 쓴다.
     */
    private void writeSkill(Path skillDir, SkillBundle bundle) throws IOException {
        createDirectory(skillDir);
        writeFile(skillDir.resolve(SKILL_MD), bundle.skillMd(), FILE_PERMISSIONS);
        for (SkillFile file : bundle.files()) {
            Path target = resolveInside(skillDir, file.path());
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

    /** 버전 디렉터리 안의 스킬 이름들이다. 규칙에 맞는 이름의 디렉터리만 세고 심볼릭 링크는 거절한다. */
    private static List<String> skillNames(Path versionDir) throws IOException {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(versionDir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (!SKILL_NAME.matcher(name).matches()) {
                    continue;
                }
                requireDirectory(entry);
                names.add(name);
            }
        }
        names.sort(Comparator.naturalOrder());
        return names;
    }

    private static List<String> versionNames(Path profileDir) {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(profileDir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (VERSION.matcher(name).matches() && Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    names.add(name);
                }
            }
        } catch (IOException ex) {
            throw storeFailure(ex);
        }
        return names;
    }

    private static boolean isPublished(Path versionDir) {
        return Files.isRegularFile(versionDir.resolve(PUBLISHED_MARKER), LinkOption.NOFOLLOW_LINKS);
    }

    private String newVersionName(Path profileDir) {
        long millis = clock.millis();
        for (String existing : versionNames(profileDir)) {
            Matcher matcher = VERSION.matcher(existing);
            if (matcher.matches()) {
                millis = Math.max(millis, Long.parseLong(matcher.group(1)) + 1);
            }
        }
        return "v" + String.format("%013d", millis) + "-" + randomSuffix();
    }

    private static String randomSuffix() {
        StringBuilder suffix = new StringBuilder(VERSION_RANDOM_CHARS);
        for (int i = 0; i < VERSION_RANDOM_CHARS; i++) {
            suffix.append(VERSION_ALPHABET.charAt(RANDOM.nextInt(VERSION_ALPHABET.length())));
        }
        return suffix.toString();
    }

    private Path profileDir(String profile) {
        requireProfile(profile);
        return resolveInside(root, profile);
    }

    private Path versionDir(String profile, String version) {
        requireVersion(version);
        return resolveInside(profileDir(profile), version);
    }

    /** 정규화한 결과가 {@code base} 아래가 아니면 거절한다. 이름 검사를 지나온 값도 한 번 더 본다. */
    private Path resolveInside(Path base, String relative) {
        Path resolved = base.resolve(relative).normalize();
        if (!resolved.startsWith(base) || resolved.equals(base) || !resolved.startsWith(root)) {
            throw validation("skill path escapes the skill root");
        }
        return resolved;
    }

    private static void requireProfile(String profile) {
        if (!HermesProfileName.isValid(profile)) {
            throw validation("profile name is not a valid Hermes profile");
        }
    }

    private static void requireVersion(String version) {
        if (version == null || !VERSION.matcher(version).matches()) {
            throw validation("skill version name is invalid");
        }
    }

    private static void requireDirectory(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("skill path is not a plain directory: " + path.getFileName());
        }
    }

    private static void requireRegularFile(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("skill path is not a plain file: " + path.getFileName());
        }
    }

    private static void createDirectory(Path dir) throws IOException {
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            requireDirectory(dir);
            return;
        }
        Files.createDirectories(dir);
        setPermissions(dir, DIRECTORY_PERMISSIONS);
    }

    private static void writeFile(Path file, String content) throws IOException {
        writeFile(file, content, FILE_PERMISSIONS);
    }

    private static void writeFile(Path file, String content, Set<PosixFilePermission> permissions) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
        setPermissions(file, permissions);
    }

    /** POSIX 권한을 주지 못하는 파일 시스템에서는 그대로 둔다. */
    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // POSIX 가 아닌 파일 시스템이다. 운영은 Linux 라 여기 오지 않는다.
        }
    }

    private void deleteForPrune(Path dir) {
        try {
            deleteRecursively(dir);
        } catch (RuntimeException ex) {
            log.warn("옛 스킬 버전을 지우지 못했다 path={}", dir.getFileName(), ex);
        }
    }

    private static void deleteQuietly(Path dir) {
        try {
            deleteRecursively(dir);
        } catch (RuntimeException ex) {
            log.warn("쓰다 실패한 스킬 임시 디렉터리를 지우지 못했다 path={}", dir.getFileName(), ex);
        }
    }

    /** 심볼릭 링크는 따라가지 않고 링크 자체만 지운다. 없으면 그냥 지나간다. */
    private static void deleteRecursively(Path dir) {
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

    private static ApiException validation(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }

    private static ApiException storeFailure(IOException cause) {
        return new ApiException(ErrorCode.INTERNAL_ERROR, "could not access the skill store", cause);
    }

    private static String stripTrailingSlash(String path) {
        String stripped = path.strip();
        while (stripped.length() > 1 && stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }
}
