package com.bifos.assistant.workspace.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param root directory holding the agent definition repository, mounted read only
 * @param briefingLimit how many characters of a workspace guide may be injected into one run
 */
@ConfigurationProperties(prefix = "assistant.workspace")
public record WorkspaceProperties(String root, int briefingLimit) {

    public WorkspaceProperties {
        briefingLimit = briefingLimit <= 0 ? 32_000 : briefingLimit;
    }
}
