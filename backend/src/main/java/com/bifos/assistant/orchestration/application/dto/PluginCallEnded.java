package com.bifos.assistant.orchestration.application.dto;

/**
 * plugin 의 도구 기록 하나가 알린 도구 호출의 끝이다. 서명을 확인한 값만 담는다.
 *
 * <p>화면 넘기기가 이 사건으로 그 플러그인의 다음 호출이 넘기기 없이 끝났는지 본다({@code docs/features/mobile-default-chat/design.md}
 * 의 「플러그인 성공으로 닫기」). 네 값으로 판정 줄의 dedupe key 를 만들 수 있다. 이 사건을 내는 쪽은 판정 줄을 모른다.
 *
 * @param profileName 토큰이 증명한 profile
 * @param rootSessionId 서명을 확인한 루트 session
 * @param sessionId 서명을 확인한, 도구를 부른 session
 * @param toolCallId 서명을 확인한 Hermes 의 도구 호출 번호. 빈 글이 아니다
 * @param failed 도구가 실패했는가
 */
public record PluginCallEnded(String profileName, String rootSessionId, String sessionId, String toolCallId, boolean failed) {
}
