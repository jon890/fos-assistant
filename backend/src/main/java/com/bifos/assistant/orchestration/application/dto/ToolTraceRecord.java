package com.bifos.assistant.orchestration.application.dto;

import java.time.Instant;

/**
 * plugin 이 보낸 도구 기록 하나에서 서명을 확인한 값이다(hermes/plugins/fos-ctx/README.md 「도구 기록을 보낸다」).
 *
 * <p>{@code errorMessage} 는 가리기 전의 원문이다. 받는 서비스가 가린 뒤 버리고, 저장하거나 로그에 남기지 않는다.
 *
 * @param rootSessionId 서명을 확인한 루트 session
 * @param sessionId 서명을 확인한, 도구를 부른 session
 * @param toolCallId 서명을 확인한 Hermes 의 도구 호출 번호. 코드 실행 안의 호출처럼 없으면 빈 글이다
 * @param toolName 도구의 등록 이름
 * @param durationMs hook 이 잰 걸린 시간. 모르면 null
 * @param failed 도구가 실패했는가
 * @param errorType 오류 종류. 없으면 null
 * @param errorMessage 오류 글 원문. 없으면 null
 * @param startedAt 도구가 시작한 시각. 플러그인 호출 뒤인지 확인하는 데 쓴다. 모르면 null
 */
public record ToolTraceRecord(
    String rootSessionId,
    String sessionId,
    String toolCallId,
    String toolName,
    Long durationMs,
    boolean failed,
    String errorType,
    String errorMessage,
    Instant startedAt) {

    @Override
    public String toString() {
        // 오류 글 원문이 로그로 새지 않게 한다(스펙 run-trace REQ6).
        return "ToolTraceRecord[toolName=" + toolName + ", failed=" + failed + "]";
    }
}
