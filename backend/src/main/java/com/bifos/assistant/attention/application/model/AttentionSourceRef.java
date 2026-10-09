package com.bifos.assistant.attention.application.model;

import java.time.Instant;

/**
 * 판정이 읽은 원래 기록 하나다.
 *
 * @param source 출처 이름. {@code backend/docs/flow.md} 의 「왜 보였는가」 의 「출처 이름」 표의 글이다
 * @param ref 그 기록의 참조. 문맥 묶음의 참조와 같은 형식이다(예: {@code execution:812})
 * @param asOf 그 기록을 어느 시각의 것으로 읽었는지
 */
public record AttentionSourceRef(String source, String ref, Instant asOf) {}
