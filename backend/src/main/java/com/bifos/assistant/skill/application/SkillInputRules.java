package com.bifos.assistant.skill.application;

import static com.bifos.assistant.skill.application.SkillService.MAX_CHARS_PER_FILE;
import static com.bifos.assistant.skill.application.SkillService.MAX_DESCRIPTION_CHARS;
import static com.bifos.assistant.skill.application.SkillService.MAX_FILES;
import static com.bifos.assistant.skill.application.SkillService.MAX_TOTAL_BYTES;

import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.domain.SkillBundle;
import com.bifos.assistant.skill.domain.SkillFile;
import com.bifos.assistant.skill.infra.SkillFilePaths;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 스킬 저장 요청의 {@code SKILL.md}, 파일 목록, 크기와 함께 실리는 스킬의 비밀 요청 칸을 검사한다. 한도 값은 {@link SkillService} 가 갖는다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class SkillInputRules {

    static SkillFrontmatter requireSkillMd(String name, String skillMd) {
        if (skillMd == null || skillMd.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "SKILL.md is required");
        }
        if (skillMd.length() > MAX_CHARS_PER_FILE) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "SKILL.md can be at most " + MAX_CHARS_PER_FILE + " characters");
        }
        SkillFrontmatter frontmatter = SkillFrontmatter.parse(skillMd);
        if (!name.equals(frontmatter.name())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "SKILL.md frontmatter name must equal the skill name");
        }
        if (frontmatter.rawDescriptionLength() > MAX_DESCRIPTION_CHARS) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "SKILL.md description can be at most " + MAX_DESCRIPTION_CHARS + " characters");
        }
        if (!frontmatter.hasBody()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "SKILL.md must have content after the frontmatter");
        }
        // Hermes 는 이 칸에 적힌 이름으로 profile 의 환경 값과 파일을 셸 실행 공간에 넣는다(ADR-086).
        if (frontmatter.requestsSecrets()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "SKILL.md frontmatter must not request environment values or credential files");
        }
        return frontmatter;
    }

    /**
     * 새 버전에 함께 실리는 기존 스킬 가운데 비밀 요청 칸을 가진 것이 있으면 거절한다(ADR-086). 저장 검사가 생기기 전에
     * 올린 스킬이 다른 스킬의 저장을 타고 다시 게시되지 않게 하려는 것이다. 버전 디렉터리를 쓰기 전에 본다. 지우기는
     * 그런 스킬을 지울 수 있어야 하므로 이 검사를 거치지 않는다.
     */
    static void requireNoStoredSecretRequests(Map<String, SkillBundle> next, String saving) {
        List<String> names = next.values().stream()
                .filter(bundle -> !bundle.name().equals(saving))
                .filter(bundle -> SkillFrontmatter.storedRequestsSecrets(bundle.skillMd()))
                .map(SkillBundle::name)
                .sorted()
                .toList();
        if (!names.isEmpty()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "uploaded skills request environment values or credential files; fix or delete them first: "
                            + String.join(", ", names));
        }
    }

    /** 경로 규칙과 수와 중복, 다른 경로의 디렉터리인 경로를 본다. 본문이 온 파일은 글자 수도 본다. */
    static List<SkillFileInput> requireFiles(List<SkillFileInput> files) {
        List<SkillFileInput> inputs = files == null ? List.of() : files;
        if (inputs.size() > MAX_FILES) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "a skill can have at most " + MAX_FILES + " files");
        }
        List<String> paths = new ArrayList<>();
        for (SkillFileInput input : inputs) {
            if (input == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "a skill file is missing");
            }
            SkillFilePaths.requireFilePath(input.path());
            paths.add(input.path());
            if (input.content() != null && input.content().length() > MAX_CHARS_PER_FILE) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "a skill file can be at most " + MAX_CHARS_PER_FILE + " characters: " + input.path());
            }
        }
        SkillFilePaths.requireFileSet(paths);
        return inputs;
    }

    /** 본문이 빠진 파일을 지금 버전에서 채우고, 채운 뒤의 합계 크기를 본다. */
    static SkillBundle bundleOf(String name, String skillMd, List<SkillFileInput> inputs, SkillBundle current) {
        Map<String, String> currentFiles = new HashMap<>();
        if (current != null) {
            current.files().forEach(file -> currentFiles.put(file.path(), file.content()));
        }
        long totalBytes = utf8Bytes(skillMd);
        List<SkillFile> files = new ArrayList<>();
        for (SkillFileInput input : inputs) {
            String content = input.content();
            if (content == null) {
                content = currentFiles.get(input.path());
                if (content == null) {
                    throw new ApiException(
                            ErrorCode.VALIDATION_FAILED,
                            "no current content to keep for the skill file: " + input.path());
                }
            }
            totalBytes += utf8Bytes(content);
            files.add(new SkillFile(input.path(), content));
        }
        if (totalBytes > MAX_TOTAL_BYTES) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "a skill can be at most " + MAX_TOTAL_BYTES + " bytes in total");
        }
        return new SkillBundle(name, skillMd, files);
    }

    static long utf8Bytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }
}
