package com.bifos.assistant.chat.artifact.application.dto;

import com.bifos.assistant.chat.artifact.domain.type.AutoVaultState;

/**
 * 결과물 하나의 자동 보관 상태다.
 *
 * @param state 비어 있으면 자동 보관 대상이 아니다
 * @param error {@code FAILED} 일 때 그 까닭의 오류 코드. 아니면 비어 있다
 */
public record ArtifactAutoVaultStatus(AutoVaultState state, String error) {
}
