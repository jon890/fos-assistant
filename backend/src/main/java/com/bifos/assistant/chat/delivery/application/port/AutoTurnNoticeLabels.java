package com.bifos.assistant.chat.delivery.application.port;

import java.util.Map;

/**
 * 자동 turn 의 결과 도착 줄에 그릴 결과 상태를 주는 쪽이다(ADR-20261117 / native-delegation-wake).
 *
 * <p>{@code chat} 은 출처도 등록 줄도 모른다. 결과를 낸 쪽이 처음 전한 알림 줄 번호로 상태를 기억해 두고 이력 API 가 읽는다. 다시 전달해도
 * 처음 줄 번호가 그대로라 새로 고쳐도 같게 보인다.
 */
public interface AutoTurnNoticeLabels {

    /**
     * 그 대화의 알림 줄 번호마다 결과 상태 이름({@code SUCCEEDED}, {@code FAILED})이다. 주인 확인은 부르는 쪽이 마친 대화 번호로 부른다.
     */
    Map<Long, String> statusesByNotice(Long conversationId);
}
