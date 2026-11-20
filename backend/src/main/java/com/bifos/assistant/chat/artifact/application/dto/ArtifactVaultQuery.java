package com.bifos.assistant.chat.artifact.application.dto;

import com.bifos.assistant.chat.artifact.domain.VaultFileType;

/**
 * 「내 파일」 목록 한 쪽의 조건이다(스펙 workspace REQ10~REQ13).
 *
 * @param kind 종류. null 이면 전체다
 * @param text 찾을 낱말. 다듬은 값이고 없으면 null 이다
 * @param conversationId 이 대화에서 나온 파일만 본다. 주인 확인은 부르는 쪽이 한다. null 이면 전체다
 * @param before 이 번호보다 작은 보관본부터 준다. null 이면 처음부터다
 * @param limit 한 쪽의 줄 수
 */
public record ArtifactVaultQuery(VaultFileType.Kind kind, String text, Long conversationId, Long before, int limit) {
}
