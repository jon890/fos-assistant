package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.AgentStarterPrompt;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.agent.infra.AgentStarterPromptRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 에이전트의 한 줄 소개와 추천 질문을 읽고 쓴다.
 *
 * <p>성격과 달리 데이터베이스에 둔다. Hermes 가 쓰지 않고 새 대화 화면만 읽는 값이라 Hermes 를 부르지
 * 않는다. 누가 고칠 수 있는지는 성격과 같은 {@link AgentService#isEditableBy} 가 정한다.
 */
@Service
@RequiredArgsConstructor
public class StarterService {

    /** 한 에이전트가 가질 수 있는 추천 질문의 수. 화면이 같은 수를 응답으로 받는다. */
    public static final int MAX_STARTER_PROMPTS = 4;

    /** 추천 질문 한 줄의 상한. {@code agent_starter_prompt.text} 의 길이와 같다. */
    public static final int MAX_PROMPT_CHARS = 300;

    /** 한 줄 소개의 상한. {@code agent.tagline} 의 길이와 같다. */
    public static final int MAX_TAGLINE_CHARS = 200;

    private final AgentService agents;
    private final AgentRepository agentRepository;
    private final AgentStarterPromptRepository prompts;

    /** 그 에이전트의 소개와 추천 질문, 그리고 요청자가 고칠 수 있는지를 돌려준다. */
    public StarterSnapshot read(CurrentUser user, String code) {
        Agent agent = agents.requireReadable(user, code);
        return new StarterSnapshot(agent.tagline(), textsOf(agent), agents.isEditableBy(user, agent));
    }

    /**
     * 소개와 추천 질문을 한꺼번에 쓰고 쓴 결과를 돌려준다.
     *
     * <p>추천 질문은 줄마다 앞뒤 공백을 떼고 빈 줄과 {@code null} 줄을 버린 뒤에 수를 센다. 순서를 바꾸는
     * 것도 전체를 다시 쓰는 것으로 보고, 그 에이전트의 줄을 모두 지운 뒤 0부터 차례로 넣는다.
     *
     * @throws ApiException 볼 수 없거나 없는 에이전트면 {@link ErrorCode#AGENT_NOT_FOUND}, 고칠 수 없으면
     *     {@link ErrorCode#FORBIDDEN}, 수나 길이가 상한을 넘으면 {@link ErrorCode#VALIDATION_FAILED}
     */
    @Transactional
    public StarterSnapshot write(
            CurrentUser user, String code, String tagline, List<String> starterPrompts) {
        Agent agent = agents.requireReadable(user, code);
        if (!agents.isEditableBy(user, agent)) {
            throw new ApiException(
                    ErrorCode.FORBIDDEN, "only the owner of this agent or the family admin can edit it");
        }
        List<String> cleaned = clean(starterPrompts);
        if (cleaned.size() > MAX_STARTER_PROMPTS) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "an agent can have at most " + MAX_STARTER_PROMPTS + " starter prompts");
        }
        for (String text : cleaned) {
            if (text.length() > MAX_PROMPT_CHARS) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "a starter prompt can be at most " + MAX_PROMPT_CHARS + " characters");
            }
        }
        if (tagline != null && tagline.strip().length() > MAX_TAGLINE_CHARS) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "a tagline can be at most " + MAX_TAGLINE_CHARS + " characters");
        }

        agent.changeTagline(tagline);
        agentRepository.save(agent);

        // 지운 것을 먼저 내보내야 같은 차례의 새 줄이 (agent_id, position) 유일 제약에 걸리지 않는다.
        prompts.deleteByAgentId(agent.id());
        prompts.flush();
        for (int position = 0; position < cleaned.size(); position++) {
            prompts.save(AgentStarterPrompt.of(agent.id(), position, cleaned.get(position)));
        }
        return new StarterSnapshot(agent.tagline(), textsOf(agent), true);
    }

    /**
     * 여러 에이전트의 추천 질문을 한 번에 읽어 에이전트 번호별로 준다.
     *
     * <p>목록 경로가 에이전트 수만큼 질의하지 않게 한다. 추천 질문이 없는 에이전트는 빈 목록이다.
     */
    public Map<Long, List<String>> promptsOf(List<Agent> agentList) {
        Map<Long, List<String>> byAgent = new HashMap<>();
        if (agentList.isEmpty()) {
            return byAgent;
        }
        for (Agent agent : agentList) {
            byAgent.put(agent.id(), new ArrayList<>());
        }
        for (AgentStarterPrompt prompt :
                prompts.findByAgentIdInOrderByAgentIdAscPositionAsc(byAgent.keySet())) {
            byAgent.computeIfAbsent(prompt.agentId(), id -> new ArrayList<>()).add(prompt.text());
        }
        return byAgent;
    }

    private List<String> textsOf(Agent agent) {
        return prompts.findByAgentIdOrderByPositionAsc(agent.id()).stream()
                .map(AgentStarterPrompt::text)
                .toList();
    }

    /** 줄마다 앞뒤 공백을 떼고 빈 줄과 {@code null} 줄을 버린다. 목록이 {@code null} 이면 빈 목록이다. */
    private static List<String> clean(List<String> starterPrompts) {
        if (starterPrompts == null) {
            return List.of();
        }
        return starterPrompts.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(text -> !text.isEmpty())
                .toList();
    }
}
