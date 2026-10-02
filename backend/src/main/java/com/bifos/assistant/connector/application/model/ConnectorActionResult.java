package com.bifos.assistant.connector.application.model;

import com.bifos.assistant.connector.domain.type.ActionStatus;
import java.util.UUID;

/**
 * 대화에 전할 끝난 승인 줄이다(ADR-050).
 *
 * @param actionId 승인 요청 번호
 * @param title 사람에게 보일 이름. 카탈로그의 선언에 없으면 원래 이름, 그것도 없으면 등록 이름
 * @param errorCode 실패한 실행의 공통 오류 어휘, 또는 시스템이 실행하지 않고 끝낸 까닭. 없으면 null
 * @param resultText 실행 결과 본문. 모델 입력에만 넣고 알림 줄에는 싣지 않는다
 */
public record ConnectorActionResult(
        UUID actionId, String title, ActionStatus status, String errorCode, String resultText) {}
