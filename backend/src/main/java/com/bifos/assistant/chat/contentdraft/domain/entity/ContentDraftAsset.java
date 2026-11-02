package com.bifos.assistant.chat.contentdraft.domain.entity;

/**
 * 원고 revision 의 사진 블록 하나가 가리키는 첨부와 관찰이다. revision 의 {@code assets_json} 배열 항목으로 저장한다.
 *
 * @param position 블록 목록 안의 순서. 0 부터 499 까지
 * @param attachmentId 첨부 id
 * @param sourceFingerprint 원고를 저장할 때 읽은 첨부 원본의 SHA-256 16진수
 * @param observationId 가리킨 관찰 id. 관찰을 가리키지 않으면 null
 * @param observationRevision 가리킨 관찰 revision. observationId 와 함께 비거나 함께 찬다
 */
public record ContentDraftAsset(int position, Long attachmentId, String sourceFingerprint, Long observationId, Long observationRevision) {
}
