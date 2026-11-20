package com.bifos.assistant.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.context.application.ContextAssembler;
import com.bifos.assistant.context.application.dto.AssembledContext;
import com.bifos.assistant.context.application.dto.ContextBundle;
import com.bifos.assistant.context.application.dto.InstructionScope;
import com.bifos.assistant.testsupport.BackendIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

/** 모든 실행의 공통 지시문에 서비스 개념 목록이 드는지 확인한다(스펙 service-concepts REQ1-REQ5). */
@BackendIntegrationTest
class ServiceConceptsInstructionsTest {

    private static final String HEADER = "# 이 서비스의 기능";

    @Autowired
    ContextAssembler assembler;


    @ParameterizedTest
    @EnumSource(InstructionScope.class)
    @DisplayName("모든 범위의 공통 지시문에 개념 이름과 화면 주소가 있고 사용량은 없다")
    void everyScopeCarriesConceptNamesAndAddresses(InstructionScope scope) {
        String instructions = assembler.withResponseInstructions(AssembledContext.empty(), scope).instructions();

        assertThat(instructions)
            .contains(HEADER, "파일 공간", "예약 작업", "기억", "할 일과 지금 볼 것", "외부 서비스 연결", "모델 단계", "에이전트 고르기")
            .contains("/files", "/tasks/new", "/memory", "/now", "/connections", "/new?agent=<code>")
            .doesNotContain("사용량", "/usage")
            .containsSubsequence("# 답변 형식", "# 도구 호출", HEADER);
    }


    @ParameterizedTest
    @EnumSource(InstructionScope.class)
    @DisplayName("개념 목록은 사람마다 다른 기억 글보다 앞에 있다")
    void conceptListComesBeforeMemoryText(InstructionScope scope) {
        String memory = "# 지금 묻는 사람에 대해 아는 것\n- 기억 본문";
        AssembledContext context = new AssembledContext(memory, memory.length(), List.of(), ContextBundle.empty());

        String instructions = assembler.withResponseInstructions(context, scope).instructions();

        assertThat(instructions).containsSubsequence(HEADER, memory).endsWith(memory);
    }


    @ParameterizedTest
    @EnumSource(InstructionScope.class)
    @DisplayName("모델 단계와 에이전트는 바꾸지 않고 안내하며 도구가 없는 예약 작업은 만들었다고 말하지 않는다")
    void modelTierAndAgentPickAreGuidedNotChanged(InstructionScope scope) {
        String instructions = assembler.withResponseInstructions(AssembledContext.empty(), scope).instructions();
        String tierLine = lineStartingWith(instructions, "- 모델 단계");
        String agentLine = lineStartingWith(instructions, "- 에이전트 고르기");
        String scheduleLine = lineStartingWith(instructions, "- 예약 작업");

        assertThat(tierLine).contains("바꾸지 않는다", "입력창의 모델 단계 단추");
        assertThat(agentLine).contains("바뀌지 않는다", "@", "/new?agent=<code>", "agent_list");
        assertThat(scheduleLine).contains("만들었다고 말하지 않고", "/tasks/new");
        assertThat(instructions).contains("했다고 말하지 않는다");
    }


    private static String lineStartingWith(String text, String prefix) {
        return text.lines().filter(line -> line.startsWith(prefix)).findFirst().orElseThrow();
    }
}
