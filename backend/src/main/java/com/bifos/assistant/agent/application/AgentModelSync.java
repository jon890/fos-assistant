package com.bifos.assistant.agent.application;

import com.bifos.assistant.agent.domain.Agent;
import com.bifos.assistant.agent.infra.AgentRepository;
import com.bifos.assistant.hermes.HermesModelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AgentModelSync {
    private static final Logger log = LoggerFactory.getLogger(AgentModelSync.class);

    private final HermesModelClient hermes;
    private final AgentRepository agents;

    public AgentModelSync(HermesModelClient hermes, AgentRepository agents) {
        this.hermes = hermes;
        this.agents = agents;
    }

    public SyncResult sync(Agent agent) {
        String model = hermes.readModel(agent.apiBaseUrl(), agent.hermesProfile());
        if (model == null) return new SyncResult(false, false);
        String previous = agent.model();
        boolean changed = agent.syncModel(model);
        agents.save(agent);
        if (changed) {
            log.info("agent model changed code={} old={} new={}", agent.code(), previous, model);
        }
        return new SyncResult(true, changed);
    }

    public record SyncResult(boolean read, boolean changed) {}
}
