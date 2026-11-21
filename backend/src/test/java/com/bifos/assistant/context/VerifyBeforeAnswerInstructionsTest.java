package com.bifos.assistant.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.context.application.ContextAssembler;
import com.bifos.assistant.context.application.dto.AssembledContext;
import com.bifos.assistant.context.application.dto.InstructionScope;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 대화 실행의 지시문에 「확인을 마치고 답하기」 절이 드는지 확인한다(스펙 answer-quality DECISION31). */
@BackendIntegrationTest
class VerifyBeforeAnswerInstructionsTest {

    private static final String HEADER = "# 확인을 마치고 답하기";

    @Autowired
    ContextAssembler assembler;


    private String instructions(InstructionScope scope) {
        return assembler.withResponseInstructions(AssembledContext.empty(), scope).instructions();
    }


    @Test
    @DisplayName("대화 실행에는 최신 정보와 출처 절 바로 뒤에 확인 절이 들고 예약과 살펴보기에는 들지 않는다")
    void sitsAfterSearchOnlyInConversation() {
        assertThat(instructions(InstructionScope.CONVERSATION))
            .containsSubsequence("# 최신 정보와 출처", HEADER, "# 기억");
        assertThat(instructions(InstructionScope.SCHEDULED)).doesNotContain(HEADER);
        assertThat(instructions(InstructionScope.PROACTIVE)).doesNotContain(HEADER);
    }


    @Test
    @DisplayName("근거가 모자라면 폭넓게 찾아 교차 확인하고 확인할 수 있는 것은 먼저 확인하러 간다")
    void verifiesBeforeAnswering() {
        assertThat(instructions(InstructionScope.CONVERSATION))
            .contains("무엇이 확인돼야 하는지 먼저 정한다")
            .contains("근거가 없거나 하나뿐이면 web_search 도구가 있을 때 질의를 바꿔 가며 폭넓게 검색해 여러 근거를 서로 맞춰 본다")
            .contains("반대 근거")
            .contains("확인하지 못했다고 적기 전에 먼저 확인하러 간다");
    }


    @Test
    @DisplayName("미확인은 한 번만 적고 사용자에게는 꼭 필요한 하나만 묻고 맡긴 결과는 합친 결론 하나로 답한다")
    void asksOnceAndMergesDelegatedResults() {
        assertThat(instructions(InstructionScope.CONVERSATION))
            .contains("답 하나에 한 번만 모아 적고, 다음 답에서 같은 말을 되풀이하지 않는다")
            .contains("꼭 필요한 하나만 콕 집어 묻는다")
            .contains("앞 답을 되풀이하지 않고 새로 안 것을 합친 결론 하나로 답한다");
    }
}
