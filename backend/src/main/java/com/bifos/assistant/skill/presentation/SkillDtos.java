package com.bifos.assistant.skill.presentation;

import com.bifos.assistant.skill.application.SkillDetail;
import com.bifos.assistant.skill.application.SkillFileInput;
import com.bifos.assistant.skill.application.SkillList;
import com.bifos.assistant.skill.application.SkillListItem;
import com.bifos.assistant.skill.application.SkillPackagePreview;
import com.bifos.assistant.skill.application.SkillUsageSummary;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 스킬 화면이 주고받는 모양이다.
 *
 * <p>컨트롤러는 경로와 권한만 맡고 오가는 모양은 여기 둔다. 같은 저장소의 {@code AgentDtos} 가 같은
 * 규칙을 따른다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SkillDtos {

    /**
     * 스킬 목록 화면이 받는 것이다.
     *
     * @param editable 지금 요청자가 스킬을 올리고 지울 수 있는가
     * @param skillsToolsetEnabled 그 에이전트의 API 실행에 {@code skills} toolset 이 켜져 있는가
     * @param uploadLimit 그 에이전트에 올릴 수 있는 스킬 수의 한도
     */
    public record SkillListView(
            List<SkillItemView> skills, boolean editable, boolean skillsToolsetEnabled, int uploadLimit) {
        static SkillListView from(SkillList list) {
            return new SkillListView(
                    list.skills().stream().map(SkillItemView::from).toList(),
                    list.editable(),
                    list.skillsToolsetEnabled(),
                    list.uploadLimit());
        }
    }

    /**
     * 스킬 목록의 한 줄이다.
     *
     * @param source {@code UPLOADED} 나 {@code HERMES}
     * @param usage 호출 합계. 관리하는 사람에게만 주고, 없으면 칸을 빼고 보낸다
     */
    public record SkillItemView(
            String name,
            String description,
            String source,
            boolean enabled,
            @JsonInclude(JsonInclude.Include.NON_NULL) SkillUsageView usage) {
        static SkillItemView from(SkillListItem item) {
            return new SkillItemView(
                    item.name(),
                    item.description(),
                    item.source().name(),
                    item.enabled(),
                    SkillUsageView.from(item.usage()));
        }
    }

    /** 스킬 하나의 호출 수와 마지막 호출 시각이다. */
    public record SkillUsageView(long count, Instant lastInvokedAt) {
        static SkillUsageView from(SkillUsageSummary usage) {
            return usage == null ? null : new SkillUsageView(usage.count(), usage.lastInvokedAt());
        }
    }

    /**
     * 올린 스킬 하나를 편집 화면이 받는 것이다.
     *
     * @param body {@code SKILL.md} 원문 전체. 앞머리를 포함한다
     * @param files 참고 파일의 경로와 UTF-8 크기와 편집할 원문
     * @param previousSavedAt 이전 버전을 남긴 시각. 없으면 {@code null} 로 칸을 보낸다
     */
    public record SkillDetailView(
            String name, String description, String body, List<SkillFileView> files, Instant previousSavedAt) {
        static SkillDetailView from(SkillDetail detail) {
            return new SkillDetailView(
                    detail.name(),
                    detail.description(),
                    detail.body(),
                    detail.files().stream()
                            .map(file -> new SkillFileView(file.path(), file.size(), file.content()))
                            .toList(),
                    detail.previousSavedAt());
        }
    }

    public record SkillFileView(String path, long size, String content) {}

    /**
     * 스킬 묶음 미리보기다. 칸은 {@code SkillPackagePreview} 와 같고 {@code null} 칸도 보낸다.
     *
     * @param files 파일과 바뀐 표시. {@code change} 는 {@code ADDED}, {@code CHANGED}, {@code SAME}, {@code REMOVED}
     * @param problems 비어 있어야 올릴 수 있다
     */
    public record SkillPackagePreviewView(
            String name,
            String description,
            String skillMdHead,
            boolean existing,
            String baseDigest,
            boolean hasScripts,
            List<SkillPackageFileView> files,
            List<String> ignored,
            List<SkillPackageProblemView> problems) {
        static SkillPackagePreviewView from(SkillPackagePreview preview) {
            return new SkillPackagePreviewView(
                    preview.name(),
                    preview.description(),
                    preview.skillMdHead(),
                    preview.existing(),
                    preview.baseDigest(),
                    preview.hasScripts(),
                    preview.files().stream()
                            .map(file -> new SkillPackageFileView(
                                    file.path(), file.size(), file.change().name()))
                            .toList(),
                    preview.ignored(),
                    preview.problems().stream()
                            .map(problem ->
                                    new SkillPackageProblemView(problem.reason().name(), problem.path()))
                            .toList());
        }
    }

    public record SkillPackageFileView(String path, long size, String change) {}

    /** 문제 하나다. 묶음 전체의 문제면 {@code path} 를 {@code null} 로 보낸다. */
    public record SkillPackageProblemView(String reason, String path) {}

    /**
     * 스킬 하나를 통째로 쓰는 요청이다.
     *
     * <p>이름 규칙과 파일 수와 크기는 지금 버전을 읽은 뒤에 세야 하는 것이 있어 요청 본문 검증을 걸지
     * 않고 {@code SkillService} 가 판정한다.
     *
     * @param skillMd {@code SKILL.md} 원문 전체
     * @param files 참고 파일 전체. 비우면 참고 파일이 없는 스킬이다
     */
    public record WriteSkillRequest(@NotNull String skillMd, List<@Valid SkillFileRequest> files) {
        List<SkillFileInput> inputs() {
            return files == null
                    ? List.of()
                    : files.stream()
                            .map(file -> new SkillFileInput(file.path(), file.content()))
                            .toList();
        }
    }

    /**
     * 참고 파일 하나다.
     *
     * @param content 본문. 생략하면 지금 버전의 같은 경로 내용을 그대로 둔다
     */
    public record SkillFileRequest(@NotBlank String path, String content) {}

    /** 스킬을 전역으로 켜거나 끄는 요청이다. */
    public record ToggleSkillRequest(@NotNull Boolean enabled) {}
}
