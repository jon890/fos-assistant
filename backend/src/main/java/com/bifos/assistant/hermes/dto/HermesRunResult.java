package com.bifos.assistant.hermes.dto;

public record HermesRunResult(
        String runId,
        String sessionId,
        String status,
        String output,
        String model,
        String provider,
        TokenUsage usage) {

    public boolean succeeded() {
        return "completed".equalsIgnoreCase(status);
    }
}
