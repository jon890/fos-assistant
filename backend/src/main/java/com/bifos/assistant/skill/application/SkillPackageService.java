package com.bifos.assistant.skill.application;

import static com.bifos.assistant.skill.application.model.SkillPackageReason.DESCRIPTION_TOO_LONG;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.LIMIT_REACHED;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.NAME_TAKEN;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.SCRIPTS_NEED_SANDBOX;
import static com.bifos.assistant.skill.application.model.SkillPackageReason.ZIP_TOO_LARGE;
import static com.bifos.assistant.skill.infra.SkillFilePaths.SKILL_MD;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.domain.SkillBundle;
import com.bifos.assistant.skill.infra.SkillFilePaths;
import com.bifos.assistant.skill.infra.SkillPublisher;
import com.bifos.assistant.skill.infra.SkillStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 스킬 묶음 zip 의 미리보기와 올리기다. 권한을 먼저 보고 zip 을 푼다. 둘이 같은 판정을 쓰고, 올리기는 문제가 없을 때만
 * {@link SkillService#saveUploaded} 로 저장한다(ADR-20261009-skill-package).
 */
@Service
@RequiredArgsConstructor
public class SkillPackageService {

    private final SkillPackageZip zip;
    private final SkillPackageCheck check;
    private final SkillService skills;
    private final NewSkillRules newSkills;
    private final SkillStore store;
    private final SkillPublisher publisher;

    /** 저장하지 않는다. 문제가 있어도 예외 대신 문제 목록으로 준다. */
    public SkillPackagePreview preview(CurrentUser user, String code, byte[] bytes) {
        return judge(skills.requireManageable(user, code), bytes).preview();
    }

    /** 바이트를 읽지 않은 채 크기 상한을 넘은 zip 의 미리보기다. */
    public SkillPackagePreview previewTooLarge(CurrentUser user, String code) {
        skills.requireManageable(user, code);
        return new SkillPackagePreview(
                null,
                null,
                null,
                false,
                null,
                false,
                List.of(),
                List.of(),
                List.of(new SkillPackageProblem(ZIP_TOO_LARGE, null)));
    }

    /**
     * 미리보기와 같은 판정에 문제가 없으면 저장한다. 문제가 {@code SCRIPTS_NEED_SANDBOX} 하나뿐이면
     * {@link ErrorCode#SKILL_SCRIPTS_NEED_SANDBOX}, 그 밖의 문제면 첫 문제를 담은 {@link ErrorCode#SKILL_PACKAGE_INVALID} 다.
     */
    public SkillDetail upload(CurrentUser user, String code, byte[] bytes, String baseDigest) {
        Judged judged = judge(skills.requireManageable(user, code), bytes);
        List<SkillPackageProblem> problems = judged.preview().problems();
        if (problems.size() == 1 && problems.get(0).reason() == SCRIPTS_NEED_SANDBOX) {
            throw new ApiException(
                    ErrorCode.SKILL_SCRIPTS_NEED_SANDBOX, "this agent has no sandbox shell to run skill scripts");
        }
        if (!problems.isEmpty()) {
            SkillPackageProblem first = problems.get(0);
            String path = first.path() == null ? "" : ": " + first.path();
            throw new ApiException(
                    ErrorCode.SKILL_PACKAGE_INVALID, first.reason().name() + path);
        }
        CheckedSkillPackage checked = judged.checked();
        return skills.saveUploaded(
                user, code, new SkillBundle(checked.name(), checked.skillMd(), checked.files()), baseDigest);
    }

    /** 바이트를 읽지 않은 채 크기 상한을 넘은 zip 의 올리기다. 권한을 본 뒤 거절한다. */
    public void uploadTooLarge(CurrentUser user, String code) {
        skills.requireManageable(user, code);
        throw new ApiException(ErrorCode.SKILL_PACKAGE_INVALID, ZIP_TOO_LARGE.name());
    }

    /**
     * 받기와 검사 뒤, 이름이 규칙에 맞으면 같은 이름의 지금 스킬(지금 버전 우선, 없으면 표식 없는 더 새 버전)과 견주고
     * 에이전트에 따른 문제를 검사의 문제 뒤에 붙인다. 새 스킬일 때만 Hermes 이름, 개수 한도, 설명 60자를 본다.
     */
    private Judged judge(Agent agent, byte[] bytes) {
        CheckedSkillPackage checked = check.check(zip.read(bytes));
        List<SkillPackageProblem> problems = new ArrayList<>(checked.problems());
        SkillBundle current = null;
        if (validName(checked.name())) {
            String profile = agent.hermesProfile();
            Map<String, SkillBundle> uploaded = store.readCurrent(profile);
            Map<String, SkillBundle> pending = store.readPending(profile);
            current = uploaded.getOrDefault(checked.name(), pending.get(checked.name()));
            if (current == null) {
                if (newSkills.hermesNameTaken(profile, checked.name())) {
                    problems.add(new SkillPackageProblem(NAME_TAKEN, null));
                }
                if (newSkills.limitReached(uploaded, pending)) {
                    problems.add(new SkillPackageProblem(LIMIT_REACHED, null));
                }
                if (problems.stream().noneMatch(p -> p.reason() == DESCRIPTION_TOO_LONG)
                        && SkillFrontmatter.parse(checked.skillMd()).indexedDescriptionLength()
                                > SkillService.MAX_NEW_DESCRIPTION_CHARS) {
                    problems.add(new SkillPackageProblem(DESCRIPTION_TOO_LONG, SKILL_MD));
                }
            }
            if (checked.hasScripts() && !publisher.terminalEnabled(agent)) {
                problems.add(new SkillPackageProblem(SCRIPTS_NEED_SANDBOX, null));
            }
        }
        SkillPackagePreview preview = new SkillPackagePreview(
                checked.name(),
                checked.description(),
                SkillPackageDiff.head(checked.skillMd()),
                current != null,
                current == null ? null : current.digest(),
                checked.hasScripts(),
                SkillPackageDiff.files(checked.skillMd(), checked.files(), current),
                checked.ignored(),
                problems.stream().limit(SkillPackageCheck.MAX_PROBLEMS).toList());
        return new Judged(checked, preview);
    }

    private static boolean validName(String name) {
        if (name == null) {
            return false;
        }
        try {
            SkillFilePaths.requireSkillName(name);
            return true;
        } catch (ApiException e) {
            return false;
        }
    }

    /** 올리기가 저장할 파일 내용과 미리보기를 함께 들고 간다. */
    private record Judged(CheckedSkillPackage checked, SkillPackagePreview preview) {}
}
