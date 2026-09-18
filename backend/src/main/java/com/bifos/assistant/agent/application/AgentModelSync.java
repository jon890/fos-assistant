package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.domain.ModelOption;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.HermesModelClient;
import com.bifos.assistant.hermes.dto.HermesModelOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * profile 의 기본 모델을 읽어 에이전트의 1순위를 따라잡는다.
 *
 * <p>새 실행이 읽는 것은 모델 목록의 1순위이므로 그것을 갱신한다. {@code agent} 표의 {@code provider}
 * 와 {@code model} 칸도 함께 두는 것은 이미 쌓인 실행 기록이 그 값으로 해석되고 있어서다.
 *
 * <p>{@code GET /api/model/options} 가 provider 를 주지 않는 Hermes 판이 있다. 주지 않으면 provider 는
 * 건드리지 않고 그 사실을 응답에 적는다.
 */
@Service
@RequiredArgsConstructor
public class AgentModelSync {
    private static final Logger log = LoggerFactory.getLogger(AgentModelSync.class);

    private final HermesModelClient hermes;
    private final AgentRepository agents;
    private final AgentModelSelector models;

    public SyncResult sync(Agent agent) {
        HermesModelOptions read = hermes.readOptions(agent.apiBaseUrl(), agent.hermesProfile());
        if (read == null) return new SyncResult(false, false, false);

        String provider = read.provider() == null ? firstProviderOf(agent) : read.provider();
        boolean changed = models.syncFirst(agent, new ModelOption(provider, read.model()));

        String previous = agent.model();
        agent.syncModel(read.model());
        agents.save(agent);
        if (changed) {
            log.info("에이전트의 1순위 모델이 바뀌었다 code={} old={} new={}", agent.code(), previous, read.model());
        }
        return new SyncResult(true, changed, read.provider() != null);
    }

    /** Hermes 가 provider 를 주지 않았을 때 쓸 값이다. 지금 1순위의 provider 를 그대로 둔다. */
    private String firstProviderOf(Agent agent) {
        return models.optionsOf(agent).stream()
                .findFirst()
                .map(option -> option.provider())
                .orElseGet(agent::provider);
    }

    /**
     * @param read Hermes 에서 값을 읽었는가
     * @param changed 1순위가 실제로 바뀌었는가
     * @param providerRead Hermes 가 provider 도 함께 줬는가. 거짓이면 provider 는 그대로 두었다
     */
    public record SyncResult(boolean read, boolean changed, boolean providerRead) {}
}
