package com.bifos.assistant.chat.turn.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.application.AgentCapabilitiesReader;
import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.application.dto.AgentCapabilities;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentIntroduction;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.agent.domain.type.CostMode;
import com.bifos.assistant.agent.domain.type.CredentialScope;
import com.bifos.assistant.context.application.dto.AssembledContext;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.domain.type.UserRole;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 비서에서 뗀 서비스를 맡기도록 대화 지시문에 맡길 곳을 넣는지 본다(docs/features/agent-team/spec.md 의 REQ6, DECISION22).
 *
 * <p>모델이 실제로 맡기는지는 가짜 Hermes 로 보일 수 없다. 여기서는 그 판단의 근거인 지시문의 글을 고정한다.
 */
class DelegationTargetsInstructionsTest {

    private static final CurrentUser DAD = new CurrentUser(1L, "dad@example.com", "아빠", 1L, UserRole.MEMBER);

    private final AgentService agents = mock(AgentService.class);
    private final AgentCapabilitiesReader capabilities = mock(AgentCapabilitiesReader.class);
    private final DelegationTargetsInstructions instructions = new DelegationTargetsInstructions(agents, capabilities);


    private static Agent agent(long id, String code, String name, String description) {
        Agent agent = Agent.of(
            code, name, code, "http://agent-runtime.test/p/" + code, CostMode.SUBSCRIPTION,
            CredentialScope.SHARED_HOUSEHOLD, AgentVisibility.PRIVATE, DAD.id());
        if (description != null) {
            agent.changeIntroduction(AgentIntroduction.of(name, description, null, null));
        }
        ReflectionTestUtils.setField(agent, "id", id);
        return agent;
    }


    @Test
    @DisplayName("지금 에이전트를 뺀 맡길 곳과 그 서비스, 내 도구에 없는 서비스 일은 맡기라는 규칙을 지시문 끝에 넣는다")
    void appendsOtherAgentsWithServicesAndDelegationRule() {
        Agent assistant = agent(10L, "a0000000001", "비서", null);
        Agent shopping = agent(11L, "a0000000002", "네이버 쇼핑 담당", "네이버 쇼핑 일을 맡아요");
        when(agents.readableBy(DAD)).thenReturn(List.of(assistant, shopping));
        when(capabilities.read(List.of(shopping)))
            .thenReturn(Map.of(shopping.id(), new AgentCapabilities(List.of("네이버 쇼핑"), List.of())));

        AssembledContext result = instructions.appendTo(new AssembledContext("공통 지침", 5), DAD, assistant.id());

        assertThat(result.instructions())
            .startsWith("공통 지침\n\n# 맡길 수 있는 에이전트")
            .contains("web_search", "agent_delegate(mcp__fos_assistant__agent_delegate)", "대신 하지 않는다")
            .endsWith("\n\n- [a0000000002] 네이버 쇼핑 담당: 네이버 쇼핑 일을 맡아요 (서비스: 네이버 쇼핑)")
            .doesNotContain("[a0000000001]");
        assertThat(result.chars()).as("실행 기록의 문맥 길이도 더한 글과 같다").isEqualTo(result.instructions().length());
    }


    @Test
    @DisplayName("소개와 서비스가 없는 에이전트는 이름만, 스킬이 있으면 스킬을 함께 적고 줄바꿈은 공백으로 바꾼다")
    void writesEmptyFieldsOutAndKeepsOneLine() {
        assertThat(DelegationTargetsInstructions.line(agent(1L, "a1", "메모\n담당", null), AgentCapabilities.NONE))
            .isEqualTo("- [a1] 메모 담당");
        assertThat(DelegationTargetsInstructions.line(
            agent(2L, "a2", "살림", "집안일을 맡아요"),
            new AgentCapabilities(List.of("구글 캘린더", "Gmail"), List.of("meal-plan"))))
                .isEqualTo("- [a2] 살림: 집안일을 맡아요 (서비스: 구글 캘린더, Gmail / 스킬: meal-plan)");
    }


    @Test
    @DisplayName("맡길 다른 에이전트가 없으면 지시문을 바꾸지 않는다")
    void keepsInstructionsWhenNoOtherAgent() {
        Agent assistant = agent(10L, "a0000000001", "비서", null);
        when(agents.readableBy(DAD)).thenReturn(List.of(assistant));
        AssembledContext context = new AssembledContext("공통 지침", 5);

        assertThat(instructions.appendTo(context, DAD, assistant.id())).isSameAs(context);
    }


    @Test
    @DisplayName("에이전트를 읽다 실패해도 turn 을 막지 않고 지시문을 그대로 둔다")
    void keepsInstructionsWhenReadingFails() {
        when(agents.readableBy(any())).thenThrow(new IllegalStateException("db down"));
        AssembledContext context = new AssembledContext("공통 지침", 5);

        assertThat(instructions.appendTo(context, DAD, 10L)).isSameAs(context);
    }


    @Test
    @DisplayName("맡길 곳은 상한까지만 넣는다")
    void capsTargets() {
        List<Agent> many = IntStream.rangeClosed(1, DelegationTargetsInstructions.MAX_TARGETS + 5)
            .mapToObj(i -> agent(100L + i, "b" + i, "에이전트" + i, null))
            .toList();
        when(agents.readableBy(DAD)).thenReturn(many);
        when(capabilities.read(anyList())).thenReturn(Map.of());

        String section = instructions.section(DAD, 1L);

        assertThat(section.lines().filter(line -> line.startsWith("- [")).count())
            .isEqualTo(DelegationTargetsInstructions.MAX_TARGETS);
    }
}
