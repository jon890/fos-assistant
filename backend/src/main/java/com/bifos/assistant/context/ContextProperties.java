package com.bifos.assistant.context;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 실행마다 instructions 로 보낼 Memory 글자 수 상한이다. */
@ConfigurationProperties(prefix = "assistant.context")
public record ContextProperties(long maxChars) {
}
