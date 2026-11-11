package com.bifos.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.agent.domain.AgentIntroduction;
import com.bifos.assistant.agent.domain.type.AgentColor;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 에이전트 소개의 판정이 스펙의 상한과 목록을 지키는지 본다(docs/features/agent-team/spec.md 의 REQ1). */
class AgentIntroductionTest {

    @Test
    @DisplayName("앞뒤 공백을 떼고 빈 소개와 아이콘은 비운다")
    void stripsAndBlanksToNull() {
        AgentIntroduction introduction = AgentIntroduction.of("  쇼핑 담당 ", "   ", "", null);

        assertThat(introduction.name()).isEqualTo("쇼핑 담당");
        assertThat(introduction.description()).isNull();
        assertThat(introduction.icon()).isNull();
        assertThat(introduction.color()).isNull();
    }


    @Test
    @DisplayName("목록 안의 아이콘과 상한 안의 소개를 받는다")
    void acceptsListedIconAndDescriptionAtLimit() {
        String description = "가".repeat(AgentIntroduction.MAX_DESCRIPTION_CHARS);

        AgentIntroduction introduction = AgentIntroduction.of("쇼핑 담당", description, "🛍️", AgentColor.ROSE);

        assertThat(introduction.description()).isEqualTo(description);
        assertThat(introduction.icon()).isEqualTo("🛍️");
        assertThat(introduction.color()).isEqualTo(AgentColor.ROSE);
    }


    @Test
    @DisplayName("상한을 넘는 소개, 목록 밖 아이콘, 빈 이름은 VALIDATION_FAILED 다")
    void rejectsOverLimitUnknownIconAndBlankName() {
        String tooLong = "가".repeat(AgentIntroduction.MAX_DESCRIPTION_CHARS + 1);

        assertValidationFailed(() -> AgentIntroduction.of("쇼핑 담당", tooLong, null, null));
        assertValidationFailed(() -> AgentIntroduction.of("쇼핑 담당", null, "A", null));
        assertValidationFailed(() -> AgentIntroduction.of(" ", null, null, null));
        assertValidationFailed(() -> AgentIntroduction.of("가".repeat(AgentIntroduction.MAX_NAME_CHARS + 1), null, null, null));
    }


    @Test
    @DisplayName("기본 비서의 소개는 소개 상한 안이다")
    void primaryDescriptionFitsLimit() {
        assertThat(AgentIntroduction.of(
            AgentIntroduction.PRIMARY_NAME, AgentIntroduction.PRIMARY_DESCRIPTION, null, null).description())
                .isEqualTo(AgentIntroduction.PRIMARY_DESCRIPTION);
    }


    private static void assertValidationFailed(Runnable call) {
        assertThatThrownBy(call::run)
            .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
