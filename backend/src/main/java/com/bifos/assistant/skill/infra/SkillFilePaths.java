package com.bifos.assistant.skill.infra;

import static com.bifos.assistant.skill.infra.SkillFiles.validation;

import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.domain.SkillBundle;
import com.bifos.assistant.skill.domain.SkillFile;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 올린 스킬의 이름과 파일 경로가 규칙에 맞는지 판정한다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SkillFilePaths {

    public static final String SKILL_MD = "SKILL.md";

    /** 새 스킬 화면의 경로라 스킬 이름으로 쓸 수 없다. */
    public static final String RESERVED_SKILL_NAME = "new";

    /** 스킬 이름. 버전 디렉터리 안에서 스킬 디렉터리를 고를 때도 쓴다. */
    static final Pattern SKILL_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    /** 파일 경로의 한 조각. 점으로 시작하지 못하므로 {@code ..} 과 숨은 파일이 들어오지 못한다. */
    private static final Pattern PATH_SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    private static final int MAX_PATH_SEGMENTS = 4;
    private static final int MAX_PATH_LENGTH = 200;

    /** 두 조각 이상인 경로의 첫 조각이 될 수 있는 디렉터리다. */
    private static final Set<String> FILE_DIRECTORIES = Set.of("references", "templates", "scripts", "assets");

    private static final String SCRIPTS_PREFIX = "scripts/";

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

    static boolean isValidFilePath(String path) {
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

    /** 이름, {@code SKILL.md}, 파일 경로와 그 묶음을 본다. 아니면 {@link ErrorCode#VALIDATION_FAILED} 다. */
    static void requireBundle(SkillBundle bundle) {
        requireSkillName(bundle.name());
        if (bundle.skillMd() == null) {
            throw validation("skill bundle does not match its name");
        }
        bundle.files().forEach(file -> requireFilePath(file.path()));
        requireFileSet(bundle.files().stream().map(SkillFile::path).toList());
    }
}
