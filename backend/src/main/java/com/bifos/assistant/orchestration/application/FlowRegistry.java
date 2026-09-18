package com.bifos.assistant.orchestration.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

/**
 * 흐름 이름으로 흐름을 찾는다.
 *
 * <p><b>모르는 이름은 기동할 때 실패시킨다.</b> 실행할 때가 아니다. 잘못 적힌 이름을 배포한 뒤
 * 사용자가 그 에이전트를 고를 때 알게 되면 늦다.
 */
@Service
public class FlowRegistry implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FlowRegistry.class);

    private final Map<String, Flow> byName;
    private final AgentRepository agents;

    public FlowRegistry(List<Flow> flows, AgentRepository agents) {
        this.byName = flows.stream()
                .collect(Collectors.toMap(Flow::name, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        this.agents = agents;
    }

    /** 그 이름의 흐름. 없으면 null 이다. */
    public Flow find(String name) {
        return name == null ? null : byName.get(name);
    }

    /** 기동할 때 저장된 에이전트의 흐름 이름을 모두 확인한다. */
    @Override
    public void run(ApplicationArguments args) {
        List<String> unknown = agents.findAll().stream()
                .map(Agent::flow)
                .filter(flow -> flow != null && !byName.containsKey(flow))
                .distinct()
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalStateException(
                    "모르는 흐름 이름이 에이전트에 적혀 있다 unknown=%s known=%s"
                            .formatted(unknown, byName.keySet()));
        }
        log.info("등록된 흐름 flows={}", byName.keySet());
    }
}
