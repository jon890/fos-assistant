package com.bifos.assistant.skill.application;

import static com.bifos.assistant.skill.application.model.SkillPackageReason.DESCRIPTION_TOO_LONG;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.FILE_TOO_LARGE;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.FRONTMATTER_INVALID;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.NAME_INVALID;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.NESTED_SKILL_MD;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.NOT_TEXT;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.NO_BODY;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.NO_SKILL_MD;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.PATH_NOT_ALLOWED;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.SECRET_REQUEST;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.SECRET_VALUE;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.TOO_MANY_FILES;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.TOTAL_TOO_LARGE;
import static com.bifos.assistant.skill.infra.SkillFilePaths.SKILL_MD;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.skill.application.model.SkillPackageReason;
import com.bifos.assistant.skill.domain.SkillFile;
import com.bifos.assistant.skill.infra.SkillFilePaths;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 받은 스킬 묶음을 에이전트와 무관한 규칙으로 검사한다. 빼는 항목, 감싼 폴더, 경로, 글 파일, 크기, 비밀값, 앞머리를 본다.
 *
 * <p>화면이 문제를 한 번에 보여야 하므로 처음 걸린 문제로 끝내지 않고 모은다. 예외를 밖으로 던지지 않는다. 에이전트에 따라
 * 갈리는 판정(Hermes 기본 스킬 이름, 개수 한도, 새 스킬 설명 60자, {@code scripts/} 와 {@code terminal})은 하지 않는다
 * (ADR-20261009-skill-package).
 */
@Component
public class SkillPackageCheck {

    /** 모으는 문제의 상한. 넘는 것은 버린다. */
    public static final int MAX_PROBLEMS = 50;

    private static final String MACOSX_DIRECTORY = "__MACOSX";

    /**
     * 서비스 접두사로 알 수 있는 key 다. 도구 내용 가리기의 접두사와 같되, {@code task-runner} 같은 낱말 안의 {@code sk-} 를
     * 잡지 않게 앞 경계를 두고 뒤 길이를 20자 이상으로 했다.
     */
    private static final Pattern PREFIXED_SECRET =
            Pattern.compile("(?<![A-Za-z0-9_-])(?:sk-|gh[pousr]_|github_pat_|xox[a-z]*-|AIza)[A-Za-z0-9_-]{20,}");

    private static final Pattern PRIVATE_KEY_LINE = Pattern.compile("(?m)^-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----");

    public CheckedSkillPackage check(ReceivedSkillPackage received) {
        if (received.problem() != null) {
            return new CheckedSkillPackage(null, null, null, List.of(), List.of(), List.of(received.problem()));
        }
        Problems problems = new Problems();
        Map<Boolean, List<SkillPackageEntry>> split =
                received.entries().stream().collect(Collectors.partitioningBy(entry -> isIgnored(entry.path())));
        List<String> ignored =
                split.get(true).stream().map(SkillPackageEntry::path).toList();
        List<SkillPackageEntry> entries = unwrap(split.get(false));
        SkillPackageEntry skillMdEntry = entries.stream()
                .filter(entry -> entry.path().equals(SKILL_MD))
                .findFirst()
                .orElse(null);
        if (skillMdEntry == null) {
            problems.add(NO_SKILL_MD, null);
            return new CheckedSkillPackage(null, null, null, List.of(), ignored, problems.list);
        }

        List<SkillPackageEntry> allowed = allowedFiles(entries, problems);

        String skillMd = readText(skillMdEntry, problems);
        long totalBytes = skillMd == null ? 0 : skillMdEntry.content().length;
        Map<String, String> texts = new LinkedHashMap<>();
        if (skillMd != null) {
            texts.put(SKILL_MD, skillMd);
        }
        List<SkillFile> files = new ArrayList<>();
        for (SkillPackageEntry entry : allowed) {
            String content = readText(entry, problems);
            if (content == null) {
                continue;
            }
            texts.put(entry.path(), content);
            totalBytes += entry.content().length;
            if (content.length() <= SkillService.MAX_CHARS_PER_FILE) {
                files.add(new SkillFile(entry.path(), content));
            }
        }
        if (allowed.size() > SkillService.MAX_FILES) {
            problems.add(TOO_MANY_FILES, null);
        }
        if (totalBytes > SkillService.MAX_TOTAL_BYTES) {
            problems.add(TOTAL_TOO_LARGE, null);
        }

        findSecretValues(texts, problems);

        SkillFrontmatter frontmatter = skillMd == null ? null : readFrontmatter(skillMd, problems);
        return new CheckedSkillPackage(
                frontmatter == null ? null : frontmatter.name(),
                frontmatter == null ? null : frontmatter.description(),
                skillMd,
                files,
                ignored,
                problems.list);
    }

    /** 조각 하나라도 {@code .} 으로 시작하거나({@code .DS_Store}, {@code .git/…}) 첫 조각이 {@code __MACOSX} 인 항목이다. */
    private static boolean isIgnored(String path) {
        String[] segments = path.split("/", -1);
        if (segments[0].equals(MACOSX_DIRECTORY)) {
            return true;
        }
        for (String segment : segments) {
            if (segment.startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 모든 항목이 같은 맨 위 디렉터리 하나 아래에 있으면 그 디렉터리를 한 번 벗긴다. 모든 항목이 두 조각 이상이므로 맨 위
     * {@code SKILL.md} 는 없다. 돌려주는 목록은 경로 차례대로다.
     */
    private static List<SkillPackageEntry> unwrap(List<SkillPackageEntry> entries) {
        List<SkillPackageEntry> sorted = entries.stream()
                .sorted(Comparator.comparing(SkillPackageEntry::path))
                .toList();
        if (sorted.isEmpty()) {
            return sorted;
        }
        String first = firstSegment(sorted.get(0).path());
        String prefix = first + "/";
        boolean wrapped = sorted.stream().allMatch(entry -> entry.path().startsWith(prefix));
        if (!wrapped) {
            return sorted;
        }
        return sorted.stream()
                .map(entry -> new SkillPackageEntry(entry.path().substring(prefix.length()), entry.content()))
                .toList();
    }

    private static String firstSegment(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? path : path.substring(0, slash);
    }

    /**
     * {@code SKILL.md} 가 아닌 항목 가운데 경로 규칙을 통과한 것을 돌려준다. 맨 위가 아닌 {@code SKILL.md} 와 규칙에 맞지 않는
     * 경로는 문제로 남기고, 통과한 경로들이 함께 놓일 수 없으면 묶음 전체의 문제로 남긴다.
     */
    private static List<SkillPackageEntry> allowedFiles(List<SkillPackageEntry> entries, Problems problems) {
        List<SkillPackageEntry> allowed = new ArrayList<>();
        for (SkillPackageEntry entry : entries) {
            String path = entry.path();
            if (path.equals(SKILL_MD)) {
                continue;
            }
            String last = path.substring(path.lastIndexOf('/') + 1);
            if (last.equalsIgnoreCase(SKILL_MD)) {
                problems.add(NESTED_SKILL_MD, path);
                continue;
            }
            try {
                SkillFilePaths.requireFilePath(path);
                allowed.add(entry);
            } catch (ApiException e) {
                problems.add(PATH_NOT_ALLOWED, path);
            }
        }
        try {
            SkillFilePaths.requireFileSet(
                    allowed.stream().map(SkillPackageEntry::path).toList());
        } catch (ApiException e) {
            problems.add(PATH_NOT_ALLOWED, null);
        }
        return allowed;
    }

    /**
     * 바이트를 어긋남 없는 UTF-8 로 읽는다. 읽히지 않거나 NUL 이 있으면 {@code null} 이고 {@code NOT_TEXT} 를 남긴다. 글자
     * 수는 기존 저장과 같게 {@link String#length()} 로 세어 상한을 넘으면 {@code FILE_TOO_LARGE} 를 남기되 글은 돌려준다.
     */
    private static String readText(SkillPackageEntry entry, Problems problems) {
        String text;
        try {
            text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(entry.content()))
                    .toString();
        } catch (CharacterCodingException e) {
            problems.add(NOT_TEXT, entry.path());
            return null;
        }
        if (text.indexOf('\0') >= 0) {
            problems.add(NOT_TEXT, entry.path());
            return null;
        }
        if (text.length() > SkillService.MAX_CHARS_PER_FILE) {
            problems.add(FILE_TOO_LARGE, entry.path());
        }
        return text;
    }

    /** 글로 읽힌 파일마다 비밀값처럼 보이는 글이 있으면 그 파일의 {@code SECRET_VALUE} 를 남긴다. */
    private static void findSecretValues(Map<String, String> texts, Problems problems) {
        texts.forEach((path, text) -> {
            if (PREFIXED_SECRET.matcher(text).find()
                    || PRIVATE_KEY_LINE.matcher(text).find()) {
                problems.add(SECRET_VALUE, path);
            }
        });
    }

    /** 앞머리를 기존 저장과 같은 규칙으로 본다. 읽지 못하면 {@code null} 이다. */
    private static SkillFrontmatter readFrontmatter(String skillMd, Problems problems) {
        SkillFrontmatter frontmatter;
        try {
            frontmatter = SkillFrontmatter.parse(skillMd);
        } catch (ApiException e) {
            problems.add(FRONTMATTER_INVALID, SKILL_MD);
            return null;
        }
        try {
            SkillFilePaths.requireSkillName(frontmatter.name());
        } catch (ApiException e) {
            problems.add(NAME_INVALID, SKILL_MD);
        }
        if (!frontmatter.hasBody()) {
            problems.add(NO_BODY, SKILL_MD);
        }
        // Hermes 는 이 칸에 적힌 이름으로 profile 의 환경 값과 파일을 셸 실행 공간에 넣는다(ADR-086).
        if (frontmatter.requestsSecrets()) {
            problems.add(SECRET_REQUEST, SKILL_MD);
        }
        if (frontmatter.rawDescriptionLength() > SkillService.MAX_DESCRIPTION_CHARS) {
            problems.add(DESCRIPTION_TOO_LONG, SKILL_MD);
        }
        return frontmatter;
    }

    /** 상한까지만 모으는 문제 목록이다. */
    private static final class Problems {

        private final List<SkillPackageProblem> list = new ArrayList<>();

        void add(SkillPackageReason reason, String path) {
            if (list.size() < MAX_PROBLEMS) {
                list.add(new SkillPackageProblem(reason, path));
            }
        }
    }
}
