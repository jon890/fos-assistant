package com.bifos.assistant.connector.application.model;

import com.bifos.assistant.connector.domain.type.ActionStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 대화에 전할 끝난 승인 줄이다(ADR-050).
 *
 * @param actionId 승인 요청 번호
 * @param title 사람에게 보일 이름. 카탈로그의 선언에 이름이 없으면 고정 문구다. 알림 줄에 쓴다
 * @param tool 모델이 아는 도구 이름. 원래 이름이고 없으면 등록 이름이다. 모델 입력에만 쓴다
 * @param errorCode 실패한 실행의 공통 오류 어휘, 또는 시스템이 실행하지 않고 끝낸 까닭. 없으면 null
 * @param resultText 실행 결과 본문. 모델 입력에만 넣고 알림 줄에는 싣지 않는다
 * @param userId 승인 줄의 주인
 * @param executedAt 실행이 끝난 시각. 결과 머리줄의 끝난 시각과 신선도에 쓴다(ADR-071). 모르면 null
 * @param conversationGrantExpiresAt 승인하면서 그 도구를 그 대화에서 계속 허용했고 그 허락이 아직 유효하면 끝나는 시각이다.
 * 아니면 null 이다. 알림 줄과 모델 입력이 함께 알린다
 */
public record ConnectorActionResult(
    UUID actionId,
    String title,
    String tool,
    ActionStatus status,
    String errorCode,
    String resultText,
    Long userId,
    Instant executedAt,
    Instant conversationGrantExpiresAt) {

    /** 대화 허락을 모르는 호출이다. */
    public ConnectorActionResult(
        UUID actionId,
        String title,
        String tool,
        ActionStatus status,
        String errorCode,
        String resultText,
        Long userId,
        Instant executedAt) {
        this(actionId, title, tool, status, errorCode, resultText, userId, executedAt, null);
    }


    /** 유효한 대화 허락을 함께 알리는 결과인가. */
    public boolean conversationGranted() {
        return conversationGrantExpiresAt != null;
    }
}
