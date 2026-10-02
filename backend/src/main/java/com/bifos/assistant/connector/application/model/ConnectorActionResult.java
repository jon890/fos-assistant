package com.bifos.assistant.connector.application.model;

import com.bifos.assistant.connector.domain.type.ActionStatus;
import java.util.UUID;

/**
 * 대화에 전할 끝난 승인 줄이다(ADR-050).
 *
 * @param actionId 승인 요청 번호
 * @param title 사람에게 보일 이름. 카탈로그의 선언에 이름이 없으면 고정 문구다. 알림 줄에 쓴다
 * @param tool 모델이 아는 도구 이름. 원래 이름이고 없으면 등록 이름이다. 모델 입력에만 쓴다
 * @param errorCode 실패한 실행의 공통 오류 어휘, 또는 시스템이 실행하지 않고 끝낸 까닭. 없으면 null
 * @param resultText 실행 결과 본문. 모델 입력에만 넣고 알림 줄에는 싣지 않는다
 */
public record ConnectorActionResult(
        UUID actionId, String title, String tool, ActionStatus status, String errorCode, String resultText) {}
