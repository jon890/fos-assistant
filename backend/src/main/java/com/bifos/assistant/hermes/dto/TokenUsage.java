package com.bifos.assistant.hermes.dto;

/** Token counts reported by Hermes. Any field may be null when the provider omits it. */
public record TokenUsage(
        Long inputTokens, Long cachedInputTokens, Long outputTokens, Long totalTokens) {

    public static TokenUsage empty() {
        return new TokenUsage(null, null, null, null);
    }
}
