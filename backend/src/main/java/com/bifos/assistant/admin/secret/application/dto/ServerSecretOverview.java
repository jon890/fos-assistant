package com.bifos.assistant.admin.secret.application.dto;

import com.bifos.assistant.browser.application.dto.GatewaySecretStatus;
import com.bifos.assistant.notification.application.dto.VapidStatus;
import java.util.OptionalInt;

/**
 * 「서버 비밀값」 화면이 읽는 상태다. 원문은 담지 않는다.
 *
 * @param storeAvailable KEK 가 있어 저장할 수 있는가
 * @param browserGateway 브라우저 중계 서명 비밀의 상태
 * @param browserBindingCount 브라우저를 쓰는 플러그인 바인딩 수. 플러그인 목록을 읽지 못하면 비어 있다
 * @param vapid 웹 푸시 VAPID 키의 상태
 */
public record ServerSecretOverview(boolean storeAvailable, GatewaySecretStatus browserGateway, OptionalInt browserBindingCount, VapidStatus vapid) {
}
