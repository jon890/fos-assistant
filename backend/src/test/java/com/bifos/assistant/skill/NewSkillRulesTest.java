package com.bifos.assistant.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.hermes.HermesSkillClient.HermesSkill;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import com.bifos.assistant.skill.application.NewSkillRules;
import com.bifos.assistant.skill.application.SkillFrontmatter;
import com.bifos.assistant.skill.domain.SkillBundle;
import com.bifos.assistant.skill.infra.SkillProperties;
import com.bifos.assistant.skill.infra.SkillPublisher;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 새 스킬일 때만 보는 검사가 Hermes 이름, 개수 한도, 설명 60자를 각각 막는지 본다. */
class NewSkillRulesTest {

    private static final String PROFILE = "agent-profile";

    private final SkillPublisher publisher = mock(SkillPublisher.class);
    private final NewSkillRules rules =
            new NewSkillRules(publisher, new SkillProperties("/skills", "/agent/skills", null, 2));

    @Test
    @DisplayName("올린 스킬 이름은 지금 버전과 표식 없는 버전을 합친다")
    void mergesCurrentAndPendingNames() {
        Map<String, SkillBundle> current = Map.of("weekly-plan", bundle("weekly-plan"));
        Map<String, SkillBundle> pending = Map.of("weekly-plan", bundle("weekly-plan"), "shopping", bundle("shopping"));

        assertThat(NewSkillRules.uploadedNames(current, pending)).containsExactlyInAnyOrder("weekly-plan", "shopping");
    }

    @Test
    @DisplayName("Hermes 목록에 같은 이름이 있으면 SKILL_NAME_TAKEN 이다")
    void rejectsNameHermesAlreadyHas() {
        when(publisher.list(PROFILE)).thenReturn(List.of(new HermesSkill("weekly-plan", "기본 스킬", true)));

        assertThatThrownBy(() ->
                        rules.requireCreatable(PROFILE, "weekly-plan", frontmatter("가".repeat(10)), Map.of(), Map.of()))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.SKILL_NAME_TAKEN));
    }

    @Test
    @DisplayName("올린 스킬 수가 한도와 같으면 VALIDATION_FAILED 이다")
    void rejectsWhenUploadedCountReachesLimit() {
        when(publisher.list(PROFILE)).thenReturn(List.of());
        Map<String, SkillBundle> current = Map.of("first", bundle("first"));
        Map<String, SkillBundle> pending = Map.of("second", bundle("second"));

        assertThatThrownBy(
                        () -> rules.requireCreatable(PROFILE, "third", frontmatter("가".repeat(10)), current, pending))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("설명이 61자면 VALIDATION_FAILED 이고 60자면 통과한다")
    void rejectsDescriptionOver60Chars() {
        when(publisher.list(PROFILE)).thenReturn(List.of());

        assertThatThrownBy(
                        () -> rules.requireCreatable(PROFILE, "third", frontmatter("가".repeat(61)), Map.of(), Map.of()))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatCode(() -> rules.requireCreatable(PROFILE, "third", frontmatter("가".repeat(60)), Map.of(), Map.of()))
                .doesNotThrowAnyException();
    }

    private static SkillFrontmatter frontmatter(String description) {
        return SkillFrontmatter.parse("---\nname: third\ndescription: " + description + "\n---\n# 본문\n");
    }

    private static SkillBundle bundle(String name) {
        return new SkillBundle(name, "---\nname: " + name + "\ndescription: 설명\n---\n# 본문\n", List.of());
    }
}
