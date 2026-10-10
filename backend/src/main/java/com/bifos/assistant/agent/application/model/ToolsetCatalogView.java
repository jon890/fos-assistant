package com.bifos.assistant.agent.application.model;

import java.util.List;

public record ToolsetCatalogView(
        String name, String label, String description, boolean hidden, List<EnabledToolsetAgent> enabledAgents) {}
