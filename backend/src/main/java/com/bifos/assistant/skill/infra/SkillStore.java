package com.bifos.assistant.skill.infra;

import static com.bifos.assistant.skill.infra.SkillFilePaths.requireBundle;
import static com.bifos.assistant.skill.infra.SkillFiles.TEMP_PREFIX;
import static com.bifos.assistant.skill.infra.SkillFiles.createDirectory;
import static com.bifos.assistant.skill.infra.SkillFiles.deleteQuietly;
import static com.bifos.assistant.skill.infra.SkillFiles.deleteRecursively;
import static com.bifos.assistant.skill.infra.SkillFiles.randomSuffix;
import static com.bifos.assistant.skill.infra.SkillFiles.readBundle;
import static com.bifos.assistant.skill.infra.SkillFiles.requireDirectory;
import static com.bifos.assistant.skill.infra.SkillFiles.requireProfile;
import static com.bifos.assistant.skill.infra.SkillFiles.storeFailure;
import static com.bifos.assistant.skill.infra.SkillFiles.validation;
import static com.bifos.assistant.skill.infra.SkillFiles.writeFile;

import com.bifos.assistant.skill.domain.SkillBundle;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * profile 마다 둔 스킬 버전 디렉터리를 쓰고 읽고 지운다.
 *
 * <p>경로를 만드는 규칙은 이 패키지에만 있다. 디렉터리는 {@code {root}/{profile}/{버전}/{스킬}}
 * 이고 스킬 안은 {@code SKILL.md} 와 경로 규칙({@link SkillFilePaths#requireFilePath})에 맞는 파일이다. 게시에 성공한 버전에는
 * 표식 파일 {@code .published} 가 있고, 지금 버전은 표식이 있는 가장 새 디렉터리다. 스킬마다 바뀌기 전 것 하나는
 * {@link PreviousSkillStore} 가 {@code {root}/{profile}/.previous/{스킬}} 에 둔다. Hermes 설정을 읽지 않는다. 근거는 ADR-034 와
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

    /** 버전 이름. plugin 이 이 형식만 받는다. 앞 13자리는 UTC 밀리초라 이름 차례가 곧 시간 차례다. */
    private static final Pattern VERSION = Pattern.compile("v([0-9]{13})-[a-z0-9]{4}");

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
                SkillFiles.writeSkill(root, resolveInside(temporary, bundle.name()), bundle);
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
            PreviousSkillStore.requirePreviousRootIfPresent(profileDir);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(profileDir)) {
                for (Path entry : stream) {
                    String name = entry.getFileName().toString();
                    if (VERSION.matcher(name).matches()
                            || PreviousSkillStore.PREVIOUS_DIR.equals(name)
                            || name.startsWith(TEMP_PREFIX)) {
                        targets.add(entry);
                    }
                }
            }
        } catch (IOException ex) {
            throw storeFailure(ex);
        }
        targets.forEach(SkillFiles::deleteRecursively);
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

    /** 버전 디렉터리 안의 스킬 이름들이다. 규칙에 맞는 이름의 디렉터리만 세고 심볼릭 링크는 거절한다. */
    private static List<String> skillNames(Path versionDir) throws IOException {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(versionDir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (!SkillFilePaths.SKILL_NAME.matcher(name).matches()) {
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
        return SkillFiles.resolveInside(root, base, relative);
    }

    private static void requireVersion(String version) {
        if (version == null || !VERSION.matcher(version).matches()) {
            throw validation("skill version name is invalid");
        }
    }

    private void deleteForPrune(Path dir) {
        try {
            deleteRecursively(dir);
        } catch (RuntimeException ex) {
            log.warn("옛 스킬 버전을 지우지 못했다 path={}", dir.getFileName(), ex);
        }
    }

    private static String stripTrailingSlash(String path) {
        String stripped = path.strip();
        while (stripped.length() > 1 && stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }
}
