package com.bifos.assistant.hermes.dto;

/**
 * @param profileName Hermes profile that runs this turn, which also selects the credential and the
 *     API key we present
 * @param apiBaseUrl where that profile's API server answers, up to but not including {@code /v1}
 * @param input the user's message
 * @param instructions extra system text layered on top of the agent's own prompt; this is where the
 *     Control Plane injects the memory the caller is allowed to see
 * @param sessionId Hermes session to continue, or null to start a new one
 */
public record HermesRunCommand(
        String profileName, String apiBaseUrl, String input, String instructions, String sessionId) {
}
