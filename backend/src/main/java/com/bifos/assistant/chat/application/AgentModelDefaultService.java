package com.bifos.assistant.chat.application;

import com.bifos.assistant.agent.application.AgentService;
import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.chat.domain.ModelChoice;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 에이전트 기본 모델을 관리자가 읽고 저장한다(ADR-054).
 *
 * <p>저장할 때만 Hermes 목록과 견준다. 실행할 때는 저장된 값을 그대로 보낸다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentModelDefaultService {

    private final AgentService agents;
    private final ModelOptionsService modelOptions;
    private final ModelVisibilityService visibility;

    /**
     * 관리 화면이 한 번에 읽는 값이다.
     *
     * <p>Hermes 가 목록을 답하지 못해도 저장된 기본값과 숨김 목록은 돌려준다. 둘을 비우고 푸는 일에는 목록이
     * 필요 없다.
     */
    @Transactional(readOnly = true)
    public AgentModelSettings settingsFor(CurrentUser user, String agentCode) {
        Agent agent = agents.requireForAdmin(user, agentCode);
        return new AgentModelSettings(defaultOf(agent), catalogOrNull(agent), visibility.hiddenFor(user.groupId()));
    }

    /**
     * 기본 모델과 effort 를 저장한다. 세 값을 모두 비우면 profile 의 값으로 돌아간다.
     *
     * <p>검증은 트랜잭션 밖에서 끝낸다. 목록 조회가 Hermes 를 부를 수 있어 DB 연결을 쥔 채 기다리지 않게 하고,
     * 저장이 그 에이전트를 잠금 읽기로 처음 읽게 한다. 이미 저장된 모델을 그대로 두고 effort 만 바꾸는 저장은
     * 목록과 견주지 않는다. 목록에서 빠진 모델을 쓰던 에이전트도 effort 를 고칠 수 있어야 한다.
     *
     * @param choice 요청에서 {@link ModelChoice#of} 로 검증해 만든 선택. 모델 없이 effort 만 둘 수 있다
     * @throws ApiException {@code MODEL_HIDDEN}. 그룹이 숨긴 모델일 때. {@code VALIDATION_FAILED}. 그
     *     에이전트의 목록에 없는 모델일 때
     */
    public ModelChoice save(CurrentUser user, String agentCode, ModelChoice choice) {
        Agent agent = agents.requireForAdmin(user, agentCode);
        if (!choice.usesDefaultModel()) {
            visibility.requireVisible(user.groupId(), choice);
            boolean unchanged = choice.provider().equals(agent.defaultModelProvider())
                    && choice.model().equals(agent.defaultModel());
            if (!unchanged
                    && !modelOptions.optionsForAgent(user.groupId(), agent).offers(choice.provider(), choice.model())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "the selected model is unavailable for this agent");
            }
        }
        return defaultOf(agents.changeDefaultModel(
                user, agentCode, choice.provider(), choice.model(), choice.reasoningEffort()));
    }

    private ModelOptions catalogOrNull(Agent agent) {
        try {
            return modelOptions.unfilteredForAgent(agent);
        } catch (ApiException ex) {
            log.warn("모델 목록을 읽지 못해 목록 없이 설정을 돌려준다 agentCode={} code={}", agent.code(), ex.code());
            return null;
        }
    }

    private static ModelChoice defaultOf(Agent agent) {
        return ModelChoice.stored(agent.defaultModelProvider(), agent.defaultModel(), agent.defaultReasoningEffort());
    }
}
