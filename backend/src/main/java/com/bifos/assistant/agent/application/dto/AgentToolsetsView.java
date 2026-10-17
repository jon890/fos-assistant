package com.bifos.assistant.agent.application.dto;

import java.util.List;

public record AgentToolsetsView(
    List<AgentToolView> toolsets,
    List<String> unclassifiedEnabled,
    boolean shellOrFileEnabled,
    boolean skillsEnabled) {
}
