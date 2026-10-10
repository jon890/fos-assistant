package com.bifos.assistant.agent.application.model;

import com.bifos.assistant.agent.domain.AgentToolPolicy;

public record AgentToolView(
        String name,
        String label,
        String description,
        AgentToolPolicy.Tier tier,
        boolean enabled,
        boolean editable,
        boolean requiresPrivate,
        boolean hidden) {}
